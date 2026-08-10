#!/usr/bin/env python3
# Copyright 2026 H3NB
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Search and download official Material Symbols without MCP.

Examples:
    python scripts/material-symbols.py search "database search" --limit 5
    python scripts/material-symbols.py get database_search --out icon.xml
"""

import argparse
import difflib
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


METADATA_URL = (
    "https://fonts.google.com/metadata/icons"
    "?key=material_symbols&incomplete=true"
)

REPOSITORY_URL = "https://github.com/google/material-design-icons"
MATERIAL_SYMBOLS_LICENSE = "Apache-2.0"
REVISION_URL = (
    "https://api.github.com/repos/google/material-design-icons/"
    "git/ref/heads/master"
)
RAW_URL = (
    "https://raw.githubusercontent.com/google/material-design-icons/"
    "{revision}/symbols/android/{name}/{family}/{filename}"
)

FAMILIES = {
    "outlined": "materialsymbolsoutlined",
    "rounded": "materialsymbolsrounded",
    "sharp": "materialsymbolssharp",
}

FILLS = (0, 1)
WEIGHTS = (100, 200, 300, 400, 500, 600, 700)
GRADES = (-25, 0, 200)
OPTICAL_SIZES = (20, 24, 40, 48)

ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
ICON_NAME_PATTERN = re.compile(r"^[a-z0-9]+(?:_[a-z0-9]+)*$")
REVISION_PATTERN = re.compile(r"^[0-9a-fA-F]{40}$")
MAX_RESPONSE_BYTES = 1024 * 1024
MAX_METADATA_BYTES = 32 * 1024 * 1024
USER_AGENT = "JL-Mod-Plus-Material-Symbols/1.0"


class MaterialSymbolsError(RuntimeError):
    pass


def fetch_bytes(url, accept, max_bytes=MAX_RESPONSE_BYTES):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": accept,
            "User-Agent": USER_AGENT,
        },
    )

    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content = response.read(max_bytes + 1)
    except urllib.error.HTTPError as error:
        raise MaterialSymbolsError(
            f"Request failed with HTTP {error.code}: {url}"
        ) from error
    except urllib.error.URLError as error:
        raise MaterialSymbolsError(
            f"Could not reach the Material Symbols service: {error.reason}"
        ) from error

    if len(content) > max_bytes:
        raise MaterialSymbolsError(
            f"Response unexpectedly exceeds {max_bytes} bytes: {url}"
        )

    if not content:
        raise MaterialSymbolsError(f"Received an empty response: {url}")

    return content


def get_metadata():
    content = fetch_bytes(
        METADATA_URL,
        "application/json,text/plain;q=0.9",
        max_bytes=MAX_METADATA_BYTES,
    )

    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as error:
        raise MaterialSymbolsError(
            "Material Symbols metadata is not valid UTF-8."
        ) from error

    # Google metadata starts with the anti-XSSI prefix: )]}'
    if text.startswith(")]}'"):
        newline = text.find("\n")
        if newline < 0:
            raise MaterialSymbolsError(
                "Material Symbols metadata has an invalid anti-XSSI prefix."
            )
        text = text[newline + 1:]

    try:
        metadata = json.loads(text)
    except json.JSONDecodeError as error:
        raise MaterialSymbolsError(
            "Material Symbols metadata is not valid JSON."
        ) from error

    if not isinstance(metadata, dict) or not isinstance(
        metadata.get("icons"), list
    ):
        raise MaterialSymbolsError(
            "Material Symbols metadata does not contain an icon list."
        )

    return metadata


def search_icons(query, limit=10):
    query = query.lower().strip()
    if not query:
        raise MaterialSymbolsError("Search query must not be empty.")

    metadata = get_metadata()

    query_parts = query.replace("_", " ").split()
    normalized_query = query.replace(" ", "_")

    results = []

    for icon in metadata["icons"]:
        name = str(icon.get("name", "")).lower()
        if not name:
            continue

        tags = [str(value).lower() for value in icon.get("tags", [])]
        categories = [
            str(value).lower() for value in icon.get("categories", [])
        ]

        score = 0

        if query == name:
            score += 100

        if normalized_query in name:
            score += 50

        for part in query_parts:
            if part in name:
                score += 15

            if any(part in tag for tag in tags):
                score += 8

            if any(part in category for category in categories):
                score += 4

        score += int(
            difflib.SequenceMatcher(
                None,
                normalized_query,
                name,
            ).ratio() * 10
        )

        if score > 3:
            results.append((score, icon))

    results.sort(key=lambda item: item[0], reverse=True)

    unique_results = []
    seen_names = set()

    for _, icon in results:
        name = icon["name"]
        if name in seen_names:
            continue

        seen_names.add(name)
        unique_results.append(icon)

        if len(unique_results) == limit:
            break

    return unique_results


def resolve_revision(revision):
    if revision != "master":
        if not REVISION_PATTERN.fullmatch(revision):
            raise MaterialSymbolsError(
                "Revision must be 'master' or a full 40-character commit SHA."
            )
        return revision.lower()

    content = fetch_bytes(REVISION_URL, "application/vnd.github+json")

    try:
        response = json.loads(content.decode("utf-8"))
        resolved = response["object"]["sha"]
    except (KeyError, TypeError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise MaterialSymbolsError(
            "GitHub returned an invalid Material Symbols revision response."
        ) from error

    if not isinstance(resolved, str) or not REVISION_PATTERN.fullmatch(resolved):
        raise MaterialSymbolsError(
            "GitHub returned an invalid Material Symbols commit SHA."
        )

    return resolved.lower()


def validate_vector_drawable(content, expected_size):
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as error:
        raise MaterialSymbolsError(
            "Downloaded VectorDrawable is not valid UTF-8."
        ) from error

    upper_text = text.upper()
    if "<!DOCTYPE" in upper_text or "<!ENTITY" in upper_text:
        raise MaterialSymbolsError(
            "Downloaded VectorDrawable contains a prohibited DTD or entity."
        )

    try:
        root = ET.fromstring(text)
    except ET.ParseError as error:
        raise MaterialSymbolsError(
            "Downloaded content is not valid XML."
        ) from error

    android_attribute = f"{{{ANDROID_NAMESPACE}}}"

    if root.tag != "vector":
        raise MaterialSymbolsError(
            "Downloaded XML is not an Android VectorDrawable."
        )

    if (
        root.get(f"{android_attribute}width") != f"{expected_size}dp"
        or root.get(f"{android_attribute}height") != f"{expected_size}dp"
    ):
        raise MaterialSymbolsError(
            f"Expected an official {expected_size}dp Material Symbol "
            "VectorDrawable."
        )

    try:
        viewport_width = float(root.attrib[f"{android_attribute}viewportWidth"])
        viewport_height = float(root.attrib[f"{android_attribute}viewportHeight"])
    except (KeyError, ValueError) as error:
        raise MaterialSymbolsError(
            "VectorDrawable viewport dimensions must be valid numbers."
        ) from error

    if viewport_width <= 0 or viewport_height <= 0:
        raise MaterialSymbolsError(
            "VectorDrawable viewport dimensions must be positive."
        )

    paths = list(root.iter("path"))
    if not any(
        path.get(f"{android_attribute}pathData", "").strip()
        for path in paths
    ):
        raise MaterialSymbolsError(
            "VectorDrawable does not contain non-empty pathData."
        )


def build_vector_filename(
    name,
    fill=0,
    weight=400,
    grade=0,
    optical_size=24,
):
    if fill not in FILLS:
        raise MaterialSymbolsError(f"Fill must be one of: {FILLS}.")

    if weight not in WEIGHTS:
        raise MaterialSymbolsError(f"Weight must be one of: {WEIGHTS}.")

    if grade not in GRADES:
        raise MaterialSymbolsError(f"Grade must be one of: {GRADES}.")

    if optical_size not in OPTICAL_SIZES:
        raise MaterialSymbolsError(
            f"Optical size must be one of: {OPTICAL_SIZES}."
        )

    variants = []

    if weight != 400:
        variants.append(f"wght{weight}")

    if grade == -25:
        variants.append("gradN25")
    elif grade != 0:
        variants.append(f"grad{grade}")

    if fill == 1:
        variants.append("fill1")

    variant = "".join(variants)
    variant_suffix = f"_{variant}" if variant else ""

    return f"{name}{variant_suffix}_{optical_size}px.xml"


def download_vector(
    name,
    family="outlined",
    revision="master",
    fill=0,
    weight=400,
    grade=0,
    optical_size=24,
):
    if not ICON_NAME_PATTERN.fullmatch(name):
        raise MaterialSymbolsError(
            "Icon name must contain lowercase letters, numbers, and underscores."
        )

    resolved_revision = resolve_revision(revision)
    filename = build_vector_filename(
        name=name,
        fill=fill,
        weight=weight,
        grade=grade,
        optical_size=optical_size,
    )
    url = RAW_URL.format(
        revision=resolved_revision,
        name=name,
        family=FAMILIES[family],
        filename=filename,
    )
    content = fetch_bytes(url, "application/xml,text/plain;q=0.9")
    validate_vector_drawable(content, optical_size)

    return {
        "content": content,
        "family": family,
        "fill": fill,
        "filename": filename,
        "grade": grade,
        "license": MATERIAL_SYMBOLS_LICENSE,
        "optical_size": optical_size,
        "repository": REPOSITORY_URL,
        "revision": resolved_revision,
        "source_url": url,
        "sha256": hashlib.sha256(content).hexdigest(),
        "weight": weight,
    }


def positive_limit(value):
    try:
        limit = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("limit must be an integer") from error

    if limit < 1:
        raise argparse.ArgumentTypeError("limit must be at least 1")

    return limit


def build_parser():
    parser = argparse.ArgumentParser(
        description=(
            "Search official Material Symbols and download validated Android "
            "VectorDrawable XML."
        )
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    search = subparsers.add_parser(
        "search",
        help="search official Material Symbols metadata",
    )
    search.add_argument("query")
    search.add_argument("--limit", type=positive_limit, default=10)

    get = subparsers.add_parser(
        "get",
        help="download one official Android VectorDrawable",
    )
    get.add_argument("name")
    get.add_argument(
        "--family",
        choices=FAMILIES,
        default="outlined",
    )
    get.add_argument("--fill", type=int, choices=FILLS, default=0)
    get.add_argument("--weight", type=int, choices=WEIGHTS, default=400)
    get.add_argument("--grade", type=int, choices=GRADES, default=0)
    get.add_argument(
        "--optical-size",
        "--opsz",
        dest="optical_size",
        type=int,
        choices=OPTICAL_SIZES,
        default=24,
    )
    get.add_argument(
        "--revision",
        default="master",
        help="master or a full 40-character commit SHA",
    )
    get.add_argument("--out")

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()

    try:
        if args.command == "search":
            results = search_icons(args.query, args.limit)

            for icon in results:
                print(icon["name"])

                tags = icon.get("tags", [])
                if tags:
                    print("  tags:", ", ".join(tags[:8]))

                categories = icon.get("categories", [])
                if categories:
                    print("  categories:", ", ".join(categories))

                sizes = icon.get("sizes_px", [])
                if sizes:
                    print("  optical sizes:", ", ".join(map(str, sizes)))

                print()

        elif args.command == "get":
            result = download_vector(
                name=args.name,
                family=args.family,
                revision=args.revision,
                fill=args.fill,
                weight=args.weight,
                grade=args.grade,
                optical_size=args.optical_size,
            )

            if args.out:
                path = Path(args.out)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(result["content"])
                print(f"Saved: {path}")
                print(f"License: {result['license']}")
                print(f"Repository: {result['repository']}")
                print(f"Revision: {result['revision']}")
                print(f"Family: {result['family']}")
                print(f"Fill: {result['fill']}")
                print(f"Weight: {result['weight']}")
                print(f"Grade: {result['grade']}")
                print(f"Optical size: {result['optical_size']}")
                print(f"Source: {result['source_url']}")
                print(f"SHA-256: {result['sha256']}")
            else:
                sys.stdout.buffer.write(result["content"])

    except MaterialSymbolsError as error:
        parser.exit(1, f"error: {error}\n")


if __name__ == "__main__":
    main()
