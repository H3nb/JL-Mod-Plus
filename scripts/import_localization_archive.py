#!/usr/bin/env python3
"""Temporary, checked migration of the supplied localization archive."""

import hashlib
from collections import Counter
from pathlib import Path, PurePosixPath
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from .localization_audit import configuration, placeholders

RES = Path("app/src/main/res")
LEGACY = {"strings.xml", "strings_rendering.xml"}
MODULES = {"strings_" + scope + ".xml" for scope in (
    "common", "config", "controls", "library", "memory", "rendering", "runtime", "settings"
)}
INVARIANT_FILE = "strings_untranslatable.xml"
ARCHIVE_HASH = "6a24329ef2fdc66125fe9a28db635364f0f4aaa50f6f2ac6ec1ec79b757fa381"
XLIFF = "{urn:oasis:names:tc:xliff:document:1.2}g"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def catalog(files):
    resources = {}
    for name, data in files.items():
        root = ET.fromstring(data)
        require(root.tag == "resources", f"{name}: invalid root")
        for element in root:
            key = element.tag, element.get("name")
            require(key[0] in {"string", "plurals"} and key[1], f"{name}: invalid resource")
            require(key not in resources, f"{name}: duplicate {key}")
            resources[key] = (name, element)
    return resources


def text(element):
    return "".join(element.itertext())


def signature(element, formatted):
    if not formatted:
        return Counter()
    return Counter((token["index"], token["conversion"])
                   for token in placeholders(text(element))["tokens"])


def variants(element):
    if element.tag == "string":
        return {"string": element}
    items = {i.get("quantity"): i for i in element}
    require(len(items) == len(element) and "other" in items
            and set(items) <= {"zero", "one", "two", "few", "many", "other"}
            and all(i.tag == "item" for i in element),
            f"Invalid plural: {element.get('name')}")
    return items


def flag(element, name):
    return element.get(name, "true") != "false"


def migrate(archive):
    require(hashlib.sha256(archive.read_bytes()).hexdigest() == ARCHIVE_HASH,
            "Archive hash does not match the reviewed upload")
    incoming = {}
    with ZipFile(archive) as package:
        for member in package.infolist():
            path = PurePosixPath(member.filename)
            if member.is_dir() or path.parts[:4] != RES.parts:
                continue
            require(len(path.parts) == 6, f"Invalid resource path: {path}")
            locale, name = path.parts[4:]
            allowed = MODULES | ({INVARIANT_FILE} if locale == "values" else set())
            require(name in allowed and name not in incoming.setdefault(locale, {}),
                    f"Unexpected or duplicate resource: {path}")
            incoming[locale][name] = package.read(member)
    require(len(incoming) == 39 and "values" in incoming, "Expected all 39 locales")
    current = {}
    for directory in sorted(RES.iterdir()):
        if not directory.is_dir() or not directory.name.startswith("values"):
            continue
        if configuration(directory.name)["role"] not in {"source", "localized"}:
            continue
        files = {name: (directory / name).read_bytes()
                 for name in LEGACY if (directory / name).is_file()}
        if files:
            current[directory.name] = files
    require(current.keys() == incoming.keys(), "Base locale directory set changed")
    old, new = catalog(current["values"]), catalog(incoming["values"])
    require(old.keys() == new.keys(), f"Canonical identity changed: {old.keys() ^ new.keys()}")
    invariant = {key for key, (_, e) in new.items() if not flag(e, "translatable")}
    require(len(invariant) == 33, "Invariant count changed")
    for key, (_, previous) in old.items():
        name, candidate = new[key]
        require(flag(previous, "translatable") == flag(candidate, "translatable")
                and flag(previous, "formatted") == flag(candidate, "formatted"),
                f"{key}: resource attributes changed")
        require((name == INVARIANT_FILE) == (key in invariant), f"{key}: invariant module mismatch")
        if key in invariant:
            require(text(previous) == text(candidate), f"{key}: invariant value changed")
        a, b = variants(previous), variants(candidate)
        require(a.keys() == b.keys(), f"{key}: canonical plural categories changed")
        for quantity, value in a.items():
            require(signature(value, flag(previous, "formatted")) ==
                    signature(b[quantity], flag(candidate, "formatted")),
                    f"{key}/{quantity}: canonical format changed")

    for locale, files in incoming.items():
        allowed = MODULES | ({INVARIANT_FILE} if locale == "values" else set())
        require(files.keys() == allowed, f"{locale}: module set mismatch")
        for name in files:
            require(name in LEGACY or not (RES / locale / name).exists(),
                    f"{locale}/{name}: would overwrite existing module")
        if locale == "values":
            continue
        entries = catalog(files)
        require(entries.keys() == new.keys() - invariant, f"{locale}: resource coverage mismatch")
        for key, (name, element) in entries.items():
            source_name, source = new[key]
            require(name == source_name and flag(element, "translatable")
                    and flag(element, "formatted") == flag(source, "formatted"),
                    f"{locale}/{key}: module or attribute mismatch")
            source_items = variants(source)
            for quantity, item in variants(element).items():
                reference = source_items.get(quantity, source_items.get("other"))
                require(text(item).strip() and "\ufffd" not in text(item),
                        f"{locale}/{key}/{quantity}: invalid or empty text")
                require(signature(reference, flag(source, "formatted")) ==
                        signature(item, flag(element, "formatted")),
                        f"{locale}/{key}/{quantity}: format mismatch")
                require(Counter(g.get("id") for g in reference.iter(XLIFF)) ==
                        Counter(g.get("id") for g in item.iter(XLIFF)),
                        f"{locale}/{key}/{quantity}: XLIFF ID mismatch")
    # Nothing is written until every preflight check passes.
    for locale, files in incoming.items():
        directory = RES / locale
        for name in current[locale].keys() - files.keys():
            (directory / name).unlink()
        for name, data in files.items():
            (directory / name).write_bytes(data)
    print(f"Imported {len(incoming)} locales and {len(new)} canonical resources.")


if __name__ == "__main__":
    migrate(Path(sys.argv[1]))
