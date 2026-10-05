#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only inventory of repository-owned values XML; findings never fail CI."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


TEXT_TYPES = {"string", "plurals"}
RESOURCE_TYPES = TEXT_TYPES | {"string-array"}
ALIASES = {"in": "id", "iw": "he", "ji": "yi"}
# Three-letter Android UI mode qualifier, not a language. Longer qualifiers
# (night, land, v29, etc.) cannot match the bounded language pattern below.
NON_LANGUAGES = {"car"}
FORMAT = re.compile(
    r"%(?:(?P<index>[1-9]\d*)\$)?(?P<flags>[-#+ 0,(<]*)"
    r"\d*(?:\.\d+)?(?P<conversion>[bBhHsScCdoxXeEfgGaA%n]|"
    r"[tT][HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc])"
)
REFERENCE = re.compile(r"^[@?](?:[\w.]+:)?[\w]+/[\w.]+$")
STRING_REFERENCE = re.compile(r"^@(?:[\w.]+:)?string/[\w.]+$")


def configuration(directory):
    """Recognize language[-rREGION] / b+language[+Script][+REGION].

    Retain other qualifiers, with optional leading mcc/mnc. This is deliberately
    not a validator for the entire Android qualifier grammar.
    """
    if directory == "values":
        return {"directory": directory, "locale": "en", "qualifiers": [],
                "role": "source"}
    if not directory.startswith("values-"):
        raise ValueError(f"Not a values configuration: {directory}")
    parts = directory[7:].split("-")
    start = 0
    while start < len(parts) and re.fullmatch(r"(?:mcc|mnc)\d+", parts[start]):
        start += 1
    locale = None
    end = start
    if start < len(parts):
        token = parts[start]
        if token.startswith("b+"):
            tag = token[2:].split("+")
            if not re.fullmatch(r"[a-z]{2,3}", tag[0]):
                raise ValueError(f"Unsupported locale qualifier: {directory}")
            language = ALIASES.get(tag[0], tag[0])
            suffix = tag[1:]
            if suffix and re.fullmatch(r"[A-Za-z]{4}", suffix[0]):
                language += "-" + suffix.pop(0).title()
            if suffix and re.fullmatch(r"[A-Za-z]{2}|\d{3}", suffix[0]):
                language += "-" + suffix.pop(0).upper()
            if suffix:
                raise ValueError(f"Unsupported locale qualifier: {directory}")
            locale, end = language, start + 1
        elif re.fullmatch(r"[a-z]{2,3}", token) and token not in NON_LANGUAGES:
            locale, end = ALIASES.get(token, token), start + 1
            if end < len(parts) and re.fullmatch(r"r(?:[A-Z]{2}|\d{3})", parts[end]):
                locale += "-" + parts[end][1:]
                end += 1
    qualifiers = parts[:start] + parts[end:]
    return {"directory": directory, "locale": locale, "qualifiers": qualifiers,
            "role": "localized" if locale and not qualifiers else "configuration_override"}


def placeholders(text):
    """Lexical Java Formatter inputs, not full format validation or semantics.

    Literal unmatched percent signs are retained as uncertainty, not syntax
    errors. Width/precision and repeated occurrences do not change input identity.
    """
    arguments = defaultdict(set)
    tokens = []
    issues = []
    ordinary_index = 0
    previous_index = None
    unindexed = False
    offset = 0
    while True:
        offset = text.find("%", offset)
        if offset < 0:
            break
        match = FORMAT.match(text, offset)
        if not match:
            issues.append({"reason": "unparsed_percent", "offset": offset})
            offset += 1
            continue
        offset = match.end()
        conversion = match["conversion"]
        if conversion in {"%", "n"}:
            continue
        if "<" in match["flags"]:
            index = previous_index
            if index is None:
                issues.append({"reason": "reuse_without_argument", "offset": match.start()})
                continue
        elif match["index"]:
            index = int(match["index"])
        else:
            ordinary_index += 1
            index = ordinary_index
            unindexed = True
        previous_index = index
        c = conversion.lower()
        if c.startswith("t"):
            kind = "datetime"
        elif c in "dox":
            kind = "integer"
        elif c in "efg":
            kind = "float"
        else:
            kind = {"s": "string", "b": "boolean", "h": "hash",
                    "c": "character", "a": "hex_float"}[c]
        arguments[index].add(kind)
        tokens.append({"index": index, "conversion": conversion, "token": match.group()})
    ambiguous = unindexed and len(arguments) > 1
    return {"arguments": [{"index": i, "types": sorted(types)}
                          for i, types in sorted(arguments.items())],
            "tokens": tokens, "unindexed_multi_argument": ambiguous, "uncertainties": issues}


def value_record(element):
    value = "".join(element.itertext())
    return {"value": value, "reference": value.strip() if REFERENCE.fullmatch(value.strip()) else None,
            "placeholders": placeholders(value)}


def resource_record(element, config, file, ordinal):
    kind = element.get("type") if element.tag == "item" else element.tag
    if kind not in RESOURCE_TYPES:
        return None
    key = element.get("name")
    if not key:
        raise ValueError(f"Unnamed {kind} resource in {file}")
    record = {"key": key, "type": kind, "file": file, "declaration": ordinal,
              **config, "translatable": element.get("translatable", "true") != "false",
              "formatted": element.get("formatted", "true") != "false"}
    if kind == "string":
        record.update(value_record(element))
    else:
        items = []
        for item in element.findall("item"):
            data = value_record(item)
            if kind == "plurals":
                data["quantity"] = item.get("quantity")
            else:
                data["kind"] = ("string_reference" if STRING_REFERENCE.fullmatch(data["value"].strip())
                                else "other_reference" if data["reference"] else "literal")
            items.append(data)
        record["items"] = items
        if kind == "plurals":
            record["quantities"] = sorted({item["quantity"] for item in items
                                           if item["quantity"] is not None})
        else:
            record["contains_string_references"] = any(i["kind"] == "string_reference" for i in items)
            record["contains_literal_text"] = any(i["kind"] == "literal" and i["value"].strip() for i in items)
    return record


def identity(resource):
    return resource["type"], resource["key"]


def key_list(identities):
    return [{"type": kind, "key": key} for kind, key in sorted(identities)]


def location(resource):
    return {field: resource[field] for field in ("directory", "file", "declaration")}


def index_resources(resources):
    index = defaultdict(list)
    for resource in resources:
        index[identity(resource)].append(resource)
    return index


def placeholder_comparison(source, target):
    if not source["formatted"] or not target["formatted"]:
        return "formatting_disabled"
    if source["reference"] or target["reference"]:
        return "resource_reference"
    signatures = [r["placeholders"] for r in (source, target)]
    if any(s["uncertainties"] for s in signatures):
        return "unparsed_format"
    if any(s["unindexed_multi_argument"] for s in signatures):
        return "unindexed_multi_argument"
    if signatures[0]["arguments"] != signatures[1]["arguments"]:
        return "argument_mismatch"
    return None


def build_inventory(repo_root):
    res = repo_root / "app/src/main/res"
    properties = (res / "resources.properties").read_text(encoding="utf-8")
    if not re.search(r"(?m)^\s*unqualifiedResLocale\s*=\s*en\s*$", properties):
        raise ValueError("Expected resources.properties to declare unqualifiedResLocale=en")
    if not (res / "values").is_dir():
        raise ValueError("Required unqualified values directory is missing")
    resources = []
    configs = []
    for directory in sorted(p for p in res.iterdir() if p.is_dir() and
                            (p.name == "values" or p.name.startswith("values-"))):
        config = configuration(directory.name)
        declarations = []
        xml_files = sorted(directory.glob("*.xml"))
        for path in xml_files:
            file = path.relative_to(repo_root).as_posix()
            root = ET.parse(path).getroot()
            if root.tag != "resources":
                raise ValueError(f"Expected <resources> root in {file}")
            for ordinal, element in enumerate(root, 1):
                record = resource_record(element, config, file, ordinal)
                if record:
                    declarations.append(record)
        catalog_files = sorted({r["file"] for r in declarations if r["type"] in TEXT_TYPES})
        configs.append({**config, "xml_files": [p.relative_to(repo_root).as_posix() for p in xml_files],
                        "catalog_files": catalog_files, "catalog_file_count": len(catalog_files),
                        "fragmented": len(catalog_files) > 1,
                        "text_declaration_count": sum(r["type"] in TEXT_TYPES for r in declarations),
                        "array_declaration_count": sum(r["type"] == "string-array" for r in declarations)})
        resources.extend(declarations)
    source_all = [r for r in resources if r["role"] == "source"]
    source_text = [r for r in source_all if r["type"] in TEXT_TYPES]
    source_index = index_resources(source_text)
    translatable = {identity(r) for r in source_text if r["translatable"]}
    invariants = [r for r in source_all if not r["translatable"]]
    invariant_ids = {identity(r) for r in invariants}
    exact_index = defaultdict(list)
    for r in resources:
        exact_index[(r["directory"], *identity(r))].append(r)
    duplicates = [{"directory": directory, "type": kind, "key": key,
                   "declarations": [location(r) for r in items]}
                  for (directory, kind, key), items in sorted(exact_index.items()) if len(items) > 1]
    locales = []
    type_mismatches = []
    placeholder_findings = []
    locale_tags = sorted({c["locale"] for c in configs if c["locale"] and c["role"] != "source"})
    for tag in locale_tags:
        locale_configs = [c for c in configs if c["locale"] == tag and c["role"] != "source"]
        localized = [r for r in resources if r["locale"] == tag and r["role"] == "localized"
                     and r["type"] in TEXT_TYPES]
        target_index = index_resources(localized)
        matching = translatable & target_index.keys()
        missing = translatable - target_index.keys()
        extra = target_index.keys() - source_index.keys()
        locales.append({"locale": tag, "directories": [c["directory"] for c in locale_configs],
                        "base_directories": [c["directory"] for c in locale_configs if c["role"] == "localized"],
                        "qualified_directories": [c["directory"] for c in locale_configs if c["qualifiers"]],
                        "matching_source_count": len(matching), "source_total": len(translatable),
                        "missing_count": len(missing), "extra_count": len(extra),
                        "coverage_percent": round(100 * len(matching) / len(translatable), 2) if translatable else None,
                        "missing": key_list(missing), "extra": key_list(extra)})
    # Compare each exact localized configuration separately. Qualified overrides
    # are checked when present, but never inflated into base locale coverage.
    for config in configs:
        if not config["locale"] or config["role"] == "source":
            continue
        target_index = index_resources([r for r in resources if r["directory"] == config["directory"]
                                        and r["type"] in TEXT_TYPES])
        source_names = defaultdict(set)
        target_names = defaultdict(set)
        for kind, key in source_index:
            source_names[key].add(kind)
        for kind, key in target_index:
            target_names[key].add(kind)
        for key in sorted(source_names.keys() & target_names.keys()):
            if target_names[key] - source_names[key]:
                type_mismatches.append({"locale": config["locale"], "directory": config["directory"],
                                        "key": key, "source_types": sorted(source_names[key]),
                                        "localized_types": sorted(target_names[key])})
        for ident in sorted(source_index.keys() & target_index.keys()):
            if ident[0] != "string":
                continue
            src, dst = source_index[ident], target_index[ident]
            if len(src) != 1 or len(dst) != 1:
                reason = "duplicate_declaration"
            else:
                reason = placeholder_comparison(src[0], dst[0])
            if reason:
                placeholder_findings.append({"locale": config["locale"], "directory": config["directory"],
                                             "key": ident[1], "reason": reason,
                                             "source": [location(r) for r in src],
                                             "localized": [location(r) for r in dst],
                                             "source_arguments": [r["placeholders"]["arguments"] for r in src],
                                             "localized_arguments": [r["placeholders"]["arguments"] for r in dst]})
    return {"schema_version": 1, "scope": {
                "resource_root": "app/src/main/res", "default_locale": "en",
                "included": "Repository-owned values XML strings, plurals, and string-array structure",
                "excluded": "Dependencies, generated/variant resources (including app_name), other resource roots, code literals",
                "coverage": "Distinct (type, key) declarations in base locale configurations; qualified overrides are separate",
                "limitations": "No resource-reference resolution, Android string unescaping, full qualifier/Formatter validation, plural grammar, dead-resource or semantic/language judgments"},
            "source": {"locale": "en", "directory": "values",
                       "text_declaration_count": len(source_text),
                       "translatable_resource_count": len(translatable),
                       "invariant_text_declaration_count": sum(r["type"] in TEXT_TYPES for r in invariants),
                       "invariant_array_declaration_count": sum(r["type"] == "string-array" for r in invariants),
                       "translatable_resources": key_list(translatable)},
            "configurations": configs, "locales": locales, "resources": resources,
            "configuration_overrides": [location(r) | {"type": r["type"], "key": r["key"]}
                                        for r in resources if r["role"] == "configuration_override"],
            "findings": {"fragmented_configurations": [c["directory"] for c in configs if c["fragmented"]],
                         "duplicates": duplicates, "type_mismatches": type_mismatches,
                         "placeholder_findings": placeholder_findings,
                         "default_invariants": [location(r) | {"type": r["type"], "key": r["key"]} for r in invariants],
                         "localized_invariant_declarations": [location(r) | {"type": r["type"], "key": r["key"], "locale": r["locale"]}
                                                              for r in resources if r["locale"] and r["role"] != "source"
                                                              and identity(r) in invariant_ids]}}


def render_summary(report):
    source, findings = report["source"], report["findings"]
    mismatches = sum(f["reason"] == "argument_mismatch" for f in findings["placeholder_findings"])
    lines = ["# Localization inventory (report only)", "",
             "Scope: repository-owned app/src/main/res values XML; not every string in the final APK.",
             "Coverage uses exact resource type/key presence in base locale catalogs; it does not certify translation quality.",
             "", f"Default English: {source['translatable_resource_count']} translatable string/plural resources.",
             f"Locale identities: {len(report['locales'])}; locale directories: "
             f"{sum(len(row['directories']) for row in report['locales'])} (empty catalogs included).",
             f"Default invariants: {source['invariant_text_declaration_count']} text declarations; "
             f"{source['invariant_array_declaration_count']} structural arrays.",
             f"Fragmented configurations: {', '.join(findings['fragmented_configurations']) or 'none'}.",
             f"Type mismatches: {len(findings['type_mismatches'])}; clear string placeholder mismatches: {mismatches}; "
             f"skipped/ambiguous string comparisons: {len(findings['placeholder_findings']) - mismatches}.",
             f"Extra localized resources: {sum(row['extra_count'] for row in report['locales'])}; "
             f"duplicate groups: {len(findings['duplicates'])}; localized invariant declarations: "
             f"{len(findings['localized_invariant_declarations'])}.", "",
             "| Locale | Physical directories | Matching/source | Missing | Extra | Coverage |",
             "| --- | --- | --- | --- | --- | --- |"]
    for row in report["locales"]:
        coverage = f"{row['coverage_percent']:.2f}%" if row["coverage_percent"] is not None else "n/a"
        lines.append(f"| {row['locale']} | {', '.join(row['directories'])} | "
                     f"{row['matching_source_count']}/{row['source_total']} | {row['missing_count']} | "
                     f"{row['extra_count']} | {coverage} |")
    lines += ["", "| Configuration | String/plural files | Text declarations | Arrays |",
              "| --- | --- | --- | --- |"]
    for config in report["configurations"]:
        lines.append(f"| {config['directory']} | {config['catalog_file_count']} | "
                     f"{config['text_declaration_count']} | {config['array_declaration_count']} |")
    lines += ["", "Full typed missing/extra lists, file contributions, per-resource facts, plural items,",
              "and findings are in inventory.json. Plural category sets are not compared to English.",
              "Bare percent signs, references, disabled formatting, and unindexed multi-argument",
              "messages are not promoted to clear mismatches. No semantic or dead-resource verdicts."]
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="Repository root (default: parent of this script's scripts directory)")
    args = parser.parse_args(argv)
    try:
        report = build_inventory(args.repo_root.resolve())
        summary = render_summary(report)
        output = args.repo_root / "build/reports/localization"
        output.mkdir(parents=True, exist_ok=True)
        (output / "inventory.json").write_text(json.dumps(report, ensure_ascii=False, indent=2,
                                                         sort_keys=True) + "\n", encoding="utf-8", newline="\n")
        (output / "inventory.md").write_text(summary, encoding="utf-8", newline="\n")
        print(summary, end="")
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Inventory failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
