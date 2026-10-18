#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Export a read-only, context-batchable translation matrix from the localization inventory."""

import argparse
import json
from pathlib import Path
import sys

from .localization_audit import build_inventory


def _identity(resource):
    return resource["type"], resource["key"]


def _payload(resource):
    if resource is None:
        return None
    if resource["type"] == "string":
        return {
            "text": resource["value"],
            "formatted": resource["formatted"],
            "placeholders": resource["placeholders"],
        }
    return {
        "items": [
            {"quantity": item["quantity"], "text": item["value"],
             "placeholders": item["placeholders"]}
            for item in resource["items"]
        ],
        "formatted": resource["formatted"],
    }


def build_matrix(report, locales=None, prefixes=None, keys=None):
    """Represent existing XML declarations; never infer missing translations or approval."""
    available = [entry["locale"] for entry in report["locales"]]
    chosen = list(dict.fromkeys(available if locales is None else locales))
    unknown_locales = sorted(set(chosen) - set(available))
    if unknown_locales:
        raise ValueError(f"Unknown locale(s): {', '.join(unknown_locales)}")

    source = {}
    localized = {}
    for item in report["resources"]:
        if item["type"] not in {"string", "plurals"}:
            continue
        if item["role"] == "source" and item["translatable"]:
            index = _identity(item)
            if index in source:
                raise ValueError(f"Duplicate English resource: {index}")
            source[index] = item
        elif item["role"] == "localized" and item["locale"] in chosen:
            index = (item["locale"], *_identity(item))
            if index in localized:
                raise ValueError(f"Duplicate localized resource: {index}")
            localized[index] = item

    selected_keys = set(keys) if keys is not None else None
    known_keys = {key for _, key in source}
    if selected_keys is not None:
        unknown_keys = sorted(selected_keys - known_keys)
        if unknown_keys:
            raise ValueError(f"Unknown key(s): {', '.join(unknown_keys)}")

    rows = []
    for kind, key in sorted(source):
        if selected_keys is not None and key not in selected_keys:
            continue
        if prefixes and not any(key.startswith(prefix) for prefix in prefixes):
            continue
        english = source[(kind, key)]
        rows.append({
            "type": kind,
            "key": key,
            "source_file": english["file"],
            "english": _payload(english),
            "translations": {
                locale: _payload(localized.get((locale, kind, key)))
                for locale in chosen
            },
        })

    if not rows:
        raise ValueError("No translatable resources match the selected batch")
    coverage = {
        locale: {
            "present": sum(row["translations"][locale] is not None for row in rows),
            "missing": sum(row["translations"][locale] is None for row in rows),
        }
        for locale in chosen
    }
    return {
        "schema_version": 1,
        "source_locale": "en",
        "locales": chosen,
        "coverage": coverage,
        "rows": rows,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path,
                        default=Path(__file__).resolve().parents[1])
    parser.add_argument("--locale", action="append", help="Canonical locale tag; repeat as needed")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--prefix", action="append", help="Key prefix; repeat to combine")
    selection.add_argument("--keys-file", type=Path,
                           help="UTF-8 text file with one resource key per line")
    parser.add_argument("--output", type=Path, help="JSON destination; defaults under build/")
    args = parser.parse_args(argv)

    try:
        keys = None
        if args.keys_file:
            keys = [line.strip() for line in args.keys_file.read_text(encoding="utf-8").splitlines()
                    if line.strip() and not line.lstrip().startswith("#")]
        root = args.repo_root.resolve()
        report = build_inventory(root)
        matrix = build_matrix(report, locales=args.locale, prefixes=args.prefix, keys=keys)
        destination = args.output or root / "build/reports/localization/matrix.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(matrix, ensure_ascii=False, indent=2) + "\n",
                               encoding="utf-8", newline="\n")
        print(f"Exported {len(matrix['rows'])} resources and "
              f"{len(matrix['locales'])} locales to {destination}")
    except (OSError, ValueError) as error:
        print(f"Matrix export failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
