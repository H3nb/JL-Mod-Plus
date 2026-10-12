#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Apply one reviewed plain-text translation batch with byte-preserving XML edits."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import xml.parsers.expat as expat
from xml.etree.ElementTree import ParseError
from xml.sax.saxutils import escape

from .localization_audit import build_inventory, placeholders, REFERENCE


QUANTITIES = ("zero", "one", "two", "few", "many", "other")
FIELDS = {"schema_version", "locale", "source_sha256", "target_sha256", "strings",
          "plurals", "revisions", "reviewed_keys", "unresolved"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def _tag_end(data, start):
    quote = None
    for offset in range(start, len(data)):
        byte = data[offset]
        if quote:
            if byte == quote:
                quote = None
        elif byte in (34, 39):
            quote = byte
        elif byte == 62:
            return offset + 1
    raise ValueError("Unterminated XML tag")


def _nodes(data):
    """Use Expat's UTF-8 byte offsets; resource text is still parsed by inventory."""
    parser = expat.ParserCreate()
    stack, roots = [], []

    def start(tag, attrs):
        begin = parser.CurrentByteIndex
        opening_end = _tag_end(data, begin)
        node = {"tag": tag, "attrs": attrs, "start": begin, "inner": opening_end,
                "empty": data[begin:opening_end].rstrip().endswith(b"/>"),
                "children": [], "comments": False}
        if stack:
            stack[-1]["children"].append(node)
        else:
            roots.append(node)
        stack.append(node)

    def end(tag):
        node = stack.pop()
        node["close"] = node["inner"] if node["empty"] else parser.CurrentByteIndex
        node["end"] = node["inner"] if node["empty"] else _tag_end(data, node["close"])

    def comment(text):
        if stack:
            stack[-1]["comments"] = True

    def reject_doctype(*args):
        raise ValueError("DOCTYPE declarations are unsupported")

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CommentHandler = comment
    parser.StartDoctypeDeclHandler = reject_doctype
    parser.Parse(data, True)
    if len(roots) != 1 or roots[0]["tag"] != "resources":
        raise ValueError("Expected one <resources> root")
    return roots[0]


def android_text(text):
    """Input is decoded UI text, never a flattened XML or Android-escaped value."""
    if not isinstance(text, str) or any(ord(c) < 32 and c not in "\n\t\r" for c in text):
        raise ValueError("Translation must be plain Unicode text without control characters")
    if any(0xD800 <= ord(c) <= 0xDFFF or ord(c) in (0xFFFE, 0xFFFF) for c in text):
        raise ValueError("Translation contains an invalid XML character")
    value = (text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
             .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
    if value.startswith(("@", "?")):
        value = "\\" + value
    # Quoted Android text preserves leading/trailing/repeated whitespace.
    if text and (text != text.strip() or re.search(r" {2,}", text)):
        value = '"' + value + '"'
    return escape(value).encode("utf-8")


def _plain(node, record, key):
    if node["children"] or node["comments"] or record.get("reference"):
        raise ValueError(f"Unsupported inline markup, comment, or reference: {key}")


def _format(source, text, key, formatted):
    android_text(text)
    if not formatted:
        return
    target = placeholders(text)
    if source["arguments"] != target["arguments"]:
        raise ValueError(f"Placeholder argument mismatch: {key}")
    if source["unindexed_multi_argument"] or target["unindexed_multi_argument"]:
        raise ValueError(f"Unindexed multiple arguments require a direct XML review: {key}")
    if [i["reason"] for i in source["uncertainties"]] != [i["reason"] for i in target["uncertainties"]]:
        raise ValueError(f"Literal percent/format contract mismatch: {key}")
    conversions = lambda p: {(t["index"], t["conversion"].lower()) for t in p["tokens"]}
    if conversions(source) != conversions(target):
        raise ValueError(f"Placeholder conversion mismatch: {key}")


def _content_edit(data, node, text):
    value = android_text(text)
    if node["empty"]:
        opening = data[node["start"]:node["inner"]]
        return node["start"], node["end"], opening[:-2] + b">" + value + f'</{node["tag"]}>'.encode()
    return node["inner"], node["close"], value


def _splice(data, edits):
    for begin, end, value in sorted(edits, reverse=True):
        data = data[:begin] + value + data[end:]
    return data


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def prepare_batch(repo_root, batch):
    """Validate the complete proposal before returning one atomic file update."""
    if (not isinstance(batch, dict) or set(batch) != FIELDS
            or type(batch["schema_version"]) is not int or batch["schema_version"] != 1):
        raise ValueError("Expected translation batch schema version 1 and its exact fields")
    for name in ("source_sha256", "target_sha256"):
        if not isinstance(batch[name], str) or not re.fullmatch(r"[0-9a-f]{64}", batch[name]):
            raise ValueError(f"Invalid {name}")
    for name in ("strings", "plurals", "revisions"):
        if not isinstance(batch[name], dict):
            raise ValueError(f"Expected {name} object")
    if not isinstance(batch["locale"], str) or not isinstance(batch["reviewed_keys"], list) or not all(isinstance(k, str) for k in batch["reviewed_keys"]):
        raise ValueError("Expected locale tag and reviewed key list")
    if not isinstance(batch["unresolved"], list) or any(not isinstance(i, dict) or set(i) != {"key", "reason"} or not all(isinstance(v, str) and v for v in i.values()) for i in batch["unresolved"]):
        raise ValueError("Expected unresolved key/reason list")
    selected = set(batch["strings"]) | set(batch["plurals"])
    if set(batch["strings"]) & set(batch["plurals"]):
        raise ValueError("Selected key has incompatible resource types")
    if not selected <= set(batch["reviewed_keys"]) or selected & {i["key"] for i in batch["unresolved"]}:
        raise ValueError("Selected changes must be reviewed and resolved")
    if not set(batch["revisions"]) <= selected or not all(isinstance(v, str) and v.strip() for v in batch["revisions"].values()):
        raise ValueError("Revision reasons must describe selected changes")

    report = build_inventory(repo_root)
    configs = [c for c in report["configurations"] if c["role"] == "localized" and c["locale"] == batch["locale"]]
    if len(configs) != 1:
        raise ValueError("Expected one existing base directory for locale")
    source_path = repo_root / "app/src/main/res/values/strings.xml"
    target_path = repo_root / "app/src/main/res" / configs[0]["directory"] / "strings.xml"
    source_data, target_data = source_path.read_bytes(), target_path.read_bytes()
    if sha256(source_data) != batch["source_sha256"]:
        raise ValueError("Stale canonical source hash")
    source_root, target_root = _nodes(source_data), _nodes(target_data)
    resources = [r for r in report["resources"] if r["type"] in {"string", "plurals"}]
    relevant_dirs = {"values", configs[0]["directory"]}
    if any(d["directory"] in relevant_dirs for d in report["findings"]["duplicates"]):
        raise ValueError("Duplicate resource declarations")
    source = {(r["type"], r["key"]): r for r in resources if r["role"] == "source"}
    target = {(r["type"], r["key"]): r for r in resources if r["directory"] == configs[0]["directory"]}
    node_map = lambda root: {(n["attrs"].get("type", n["tag"]), n["attrs"].get("name")): n for n in root["children"]}
    source_nodes, target_nodes = node_map(source_root), node_map(target_root)
    edits, additions = [], []
    newline = b"\r\n" if b"\r\n" in target_data else b"\n"
    for kind in ("strings", "plurals"):
        resource_type = "string" if kind == "strings" else "plurals"
        for key, text in batch[kind].items():
            identity = resource_type, key
            src, dst = source.get(identity), target.get(identity)
            if not src or not src["translatable"]:
                raise ValueError(f"Unknown or invariant resource: {key}")
            if src["file"] != source_path.relative_to(repo_root).as_posix():
                raise ValueError(f"Canonical resource is outside hashed strings.xml: {key}")
            if any(r["key"] == key and r["type"] != resource_type and r["directory"] == configs[0]["directory"] for r in resources):
                raise ValueError(f"Target resource type mismatch: {key}")
            if dst and dst["file"] != target_path.relative_to(repo_root).as_posix():
                raise ValueError(f"Target resource is outside hashed strings.xml: {key}")
            if dst and (dst["formatted"] != src["formatted"] or not dst["translatable"]):
                raise ValueError(f"Target resource attribute contract mismatch: {key}")
            sn, dn = source_nodes[identity], target_nodes.get(identity)
            local_edits = []
            if resource_type == "string":
                _plain(sn, src, key)
                _format(src["placeholders"], text, key, src["formatted"])
                if dn:
                    _plain(dn, dst, key)
                    local_edits.append(_content_edit(target_data, dn, text))
                else:
                    additions.append(_splice(source_data[sn["start"]:sn["end"]], [(b-sn["start"], e-sn["start"], v) for b,e,v in [_content_edit(source_data, sn, text)]]))
            else:
                if not isinstance(text, dict) or not text or "other" not in text or not set(text) <= set(QUANTITIES):
                    raise ValueError(f"Invalid plural categories: {key}")
                src_items = {i["quantity"]: i for i in src["items"]}
                if len(src_items) != len(src["items"]) or "other" not in src_items:
                    raise ValueError(f"Invalid source plural categories: {key}")
                if len(sn["children"]) != len(src["items"]) or any(n["tag"] != "item" for n in sn["children"]):
                    raise ValueError(f"Unsupported plural child element: {key}")
                for item_node, item in zip(sn["children"], src["items"]):
                    _plain(item_node, item, key)
                for quantity, value in text.items():
                    _format(src_items.get(quantity, src_items["other"])["placeholders"], value, key, src["formatted"])
                if dn:
                    items = {n["attrs"].get("quantity"): n for n in dn["children"]}
                    if len(items) != len(dn["children"]) or not set(items) <= set(text) or any(n["tag"] != "item" for n in dn["children"]):
                        raise ValueError(f"Duplicate or removed target plural category: {key}")
                    for item in dst["items"]:
                        _plain(items[item["quantity"]], item, key)
                        local_edits.append(_content_edit(target_data, items[item["quantity"]], text[item["quantity"]]))
                    added_items = [b'        <item quantity="' + q.encode() + b'">' + android_text(text[q]) + b'</item>' for q in QUANTITIES if q in text and q not in items]
                    if added_items:
                        line_start = target_data.rfind(b"\n", 0, dn["close"]) + 1
                        if not target_data[line_start:dn["close"]].strip():
                            at, value = line_start, newline.join(added_items) + newline
                        else:
                            at, value = dn["close"], newline + newline.join(added_items) + newline + b"    "
                        local_edits.append((at, at, value))
                else:
                    opening = source_data[sn["start"]:sn["inner"]]
                    items = [b'        <item quantity="' + q.encode() + b'">' + android_text(text[q]) + b'</item>' for q in QUANTITIES if q in text]
                    additions.append(opening + newline + newline.join(items) + newline + b"    </plurals>")
            if any(target_data[b:e] != v for b,e,v in local_edits) and key not in batch["revisions"]:
                raise ValueError(f"Existing translation revision requires a reason: {key}")
            edits.extend(local_edits)
    if additions:
        edits.append((target_root["close"], target_root["close"], b"    " + (newline + b"    ").join(additions) + newline))
    proposed = _splice(target_data, edits)
    _nodes(proposed)
    if proposed != target_data and sha256(target_data) != batch["target_sha256"]:
        raise ValueError("Stale target hash")
    return target_path, target_data, proposed


def apply_batch(repo_root, batch, apply=False):
    path, current, proposed = prepare_batch(repo_root, batch)
    if apply and proposed != current:
        if path.read_bytes() != current:
            raise ValueError("Target changed after validation")
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".localization-", delete=False) as output:
            temporary = Path(output.name)
            try:
                output.write(proposed)
                output.flush()
                os.fsync(output.fileno())
                os.chmod(temporary, path.stat().st_mode)
                if path.read_bytes() != current:
                    raise ValueError("Target changed after validation")
                if sha256((repo_root / "app/src/main/res/values/strings.xml").read_bytes()) != batch["source_sha256"]:
                    raise ValueError("Canonical source changed after validation")
                os.replace(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)
    return {"file": path.relative_to(repo_root).as_posix(), "changed": proposed != current,
            "applied": apply and proposed != current, "result_sha256": sha256(proposed)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("batch", type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--apply", action="store_true", help="Write validated changes; default is dry-run")
    args = parser.parse_args(argv)
    try:
        batch = json.loads(args.batch.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
        print(json.dumps(apply_batch(args.repo_root.resolve(), batch, args.apply), sort_keys=True))
    except (OSError, ValueError, ParseError, expat.ExpatError) as error:
        print(f"Translation apply failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
