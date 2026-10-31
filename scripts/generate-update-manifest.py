#!/usr/bin/env python3
"""Publishable update index derived only from complete GitHub Releases."""
import argparse
import json
import re
import subprocess
import tempfile
from pathlib import Path

PACKAGE = "io.github.h3nb.jlmodplus"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal")
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?$")


def build_manifest(pages, metadata_for):
    releases = []
    for page in pages:
        for release in page:
            if release.get("draft"):
                continue
            tag = release.get("tag_name", "")
            if not tag.startswith("v"):
                continue
            version = tag[1:]
            if not VERSION.fullmatch(version) or release.get("prerelease") != ("-" in version):
                continue
            assets = {asset["name"]: asset for asset in release.get("assets", [])}
            if "release-info.json" not in assets:
                continue  # Legacy releases without signed-build metadata are not update candidates.
            info = metadata_for(tag)
            if (info.get("schema") != 1 or info.get("applicationId") != PACKAGE
                    or info.get("versionName") != version
                    or type(info.get("versionCode")) is not int
                    or info["versionCode"] < 1
                    or type(info.get("minSdk")) is not int or info["minSdk"] < 23):
                raise ValueError("Invalid release metadata: " + tag)
            apk_assets = []
            for abi in ABIS:
                name = f"JL-Mod-Plus-{tag}-{abi}.apk"
                if name in assets:
                    expected = f"https://github.com/H3nb/JL-Mod-Plus/releases/download/{tag}/{name}"
                    if assets[name].get("browser_download_url") != expected:
                        raise ValueError("Unexpected APK URL: " + tag)
                    apk_assets.append({"abi": abi, "url": expected})
            if not apk_assets:
                raise ValueError("Release has no installable APK: " + tag)
            releases.append({
                "versionName": version,
                "versionCode": info["versionCode"],
                "prerelease": release["prerelease"],
                "minSdk": info["minSdk"],
                "assets": apk_assets,
            })
    if not releases:
        raise ValueError("No eligible published releases")
    return {"schema": 1, "applicationId": PACKAGE, "releases": releases}


def download_metadata(tag):
    with tempfile.TemporaryDirectory() as directory:
        subprocess.run(
            ["gh", "release", "download", tag, "--repo", "H3nb/JL-Mod-Plus",
             "--pattern", "release-info.json", "--dir", directory],
            check=True,
        )
        return json.loads((Path(directory) / "release-info.json").read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--releases", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    pages = json.loads(args.releases.read_text())
    if not isinstance(pages, list):
        raise ValueError("Expected paginated GitHub releases")
    if pages and isinstance(pages[0], dict):
        pages = [pages]
    manifest = build_manifest(pages, download_metadata)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n")
    # GitHub Pages requires an entry document at the root of the deployed artifact.
    args.output.with_name("index.html").write_text(
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<title>JL-Mod Plus updates</title></head>'
        '<body><a href="updates.json">Update metadata (JSON)</a></body></html>\n'
    )


if __name__ == "__main__":
    main()
