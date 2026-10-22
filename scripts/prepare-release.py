#!/usr/bin/env python3
"""Validate APK outputs and stage the same assets for release checks and publishing."""
import argparse
import gzip
import hashlib
import json
import shutil
import zipfile
from pathlib import Path


PACKAGE = "io.github.h3nb.jlmodplus"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LABELS = {
    "universal": "Android APK - Universal",
    "arm64-v8a": "Android APK - ARM64",
    "armeabi-v7a": "Android APK - ARM 32-bit",
    "x86": "Android APK - x86",
    "x86_64": "Android APK - x86-64",
}


def apk_outputs(apk_dir, version, code):
    metadata = json.loads((apk_dir / "output-metadata.json").read_text())
    if metadata.get("applicationId") != PACKAGE:
        raise ValueError("Unexpected release application ID")
    outputs = {}
    for entry in metadata["elements"]:
        if entry.get("versionName") != version or entry.get("versionCode") != code:
            raise ValueError("Built APK identity differs from release plan")
        filters = entry.get("filters", [])
        if not filters:
            label = "universal"
        elif len(filters) == 1 and filters[0].get("filterType") == "ABI":
            label = filters[0]["value"]
        else:
            raise ValueError("Unexpected APK filters")
        if label not in LABELS or label in outputs:
            raise ValueError("Unexpected or duplicate APK variant: " + label)
        apk = apk_dir / entry["outputFile"]
        if not apk.is_file() or not apk.resolve().is_relative_to(apk_dir.resolve()):
            raise ValueError("Missing or invalid APK path: " + str(apk))
        with zipfile.ZipFile(apk) as archive:
            native = [item for item in archive.infolist()
                      if item.filename.startswith("lib/") and item.filename.endswith(".so")]
            expected = set(ABIS) if label == "universal" else {label}
            if {item.filename.split("/")[1] for item in native} != expected:
                raise ValueError("Native ABI contents differ from APK metadata: " + label)
            if any(item.compress_type != zipfile.ZIP_DEFLATED for item in native):
                raise ValueError("Release native libraries must be compressed: " + label)
        outputs[label] = apk
    if outputs.keys() != LABELS.keys():
        raise ValueError("Expected four ABI APKs and one universal APK")
    return outputs


def prepare(apk_dir, mapping, output, version, code, commit, min_sdk, repository):
    outputs = apk_outputs(apk_dir, version, code)
    if not mapping.is_file():
        raise ValueError("Missing R8 mapping")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    tag = "v" + version
    assets = []
    apks = {}
    for label in LABELS:
        target = output / f"JL-Mod-Plus-{tag}-{label}.apk"
        shutil.copyfile(outputs[label], target)
        apks[label] = target
        assets.append({"path": str(target), "label": LABELS[label]})

    compressed_mapping = output / f"JL-Mod-Plus-{tag}-r8-mapping.txt.gz"
    with mapping.open("rb") as source, compressed_mapping.open("wb") as target:
        with gzip.GzipFile(filename="", fileobj=target, mode="wb", compresslevel=6, mtime=0) as zipped:
            shutil.copyfileobj(source, zipped)
    assets.append({"path": str(compressed_mapping), "label": "R8 mapping - Developer file"})

    info = output / "release-info.json"
    info.write_text(json.dumps({
        "schema": 1, "versionName": version, "versionCode": code,
        "applicationId": PACKAGE, "minSdk": min_sdk, "commit": commit,
    }, indent=2) + "\n")
    assets.append({"path": str(info), "label": "Release metadata"})
    checksums = output / "SHA256SUMS.txt"
    with checksums.open("w") as target:
        for asset in assets:
            path = Path(asset["path"])
            with path.open("rb") as source:
                digest = hashlib.file_digest(source, "sha256").hexdigest()
            target.write(f"{digest}  {path.name}\n")
    assets.append({"path": str(checksums), "label": "SHA-256 checksums"})
    (output / "assets.json").write_text(json.dumps(assets, indent=2) + "\n")

    download = f"https://github.com/{repository}/releases/download/{tag}"

    def link(label):
        apk = apks[label]
        return f"[{LABELS[label]}]({download}/{apk.name}) — {apk.stat().st_size / 1_000_000:.2f} MB"

    android_requirement = "Android 6.0" if min_sdk == 23 else f"Android API {min_sdk}"
    notes = [
        "## Download", "",
        f"Requires {android_requirement} or newer. Download one APK.", "",
        f"- **{link('universal')}** — Choose this if you are unsure which architecture your device uses.",
        f"- **{link('arm64-v8a')}** — Smaller download for devices running ARM64 Android.", "",
        "<details>", "<summary>Other architectures</summary>", "",
        *(f"- {link(label)}" for label in ("armeabi-v7a", "x86", "x86_64")),
        "", "</details>", "",
        "<details>", "<summary>Verification and developer files</summary>", "",
        f"- [SHA-256 checksums]({download}/{checksums.name})",
        f"- [Release metadata]({download}/{info.name})",
        f"- [Compressed R8 mapping]({download}/{compressed_mapping.name}) for crash analysis. "
        "Decompress the `.gz` file before using Retrace.",
        "", "These files are not needed to install the app.", "", "</details>", "",
    ]
    (output / "release-notes.md").write_text("\n".join(notes) + "\n")
    for label, apk in apks.items():
        print(f"{label}: {apk.stat().st_size:,} bytes")
    print(f"R8 mapping: {mapping.stat().st_size:,} -> {compressed_mapping.stat().st_size:,} bytes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, default=Path("app/build/outputs/apk/emulator/release"))
    parser.add_argument("--mapping", type=Path, default=Path("app/build/outputs/mapping/emulatorRelease/mapping.txt"))
    parser.add_argument("--output", type=Path, default=Path("dist"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--min-sdk", type=int, required=True)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    prepare(args.apk_dir, args.mapping, args.output, args.version, args.version_code,
            args.commit, args.min_sdk, args.repository)


if __name__ == "__main__":
    main()
