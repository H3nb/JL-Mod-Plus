#!/usr/bin/env python3
"""Allocate a publication version from GitHub releases, not CI run numbers."""
import argparse
import json
import re
import subprocess
import tempfile
from pathlib import Path

INPUT = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc))?$")
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$")


def compare(a, b):
    def parse(value):
        match = SEMVER.fullmatch(value)
        if match is None:
            raise ValueError("Invalid SemVer: " + value)
        major, minor, patch, suffix = match.groups()
        parts = () if suffix is None else tuple(
            int(part) if part.isdigit() else part for part in suffix.split(".")
        )
        return (int(major), int(minor), int(patch)), parts
    left, lp = parse(a)
    right, rp = parse(b)
    if left != right:
        return (left > right) - (left < right)
    if not lp or not rp:
        return (not lp) - (not rp)
    for x, y in zip(lp, rp):
        if x == y:
            continue
        if isinstance(x, int) != isinstance(y, int):
            return -1 if isinstance(x, int) else 1
        return (x > y) - (x < y)
    return (len(lp) > len(rp)) - (len(lp) < len(rp))


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def list_releases(repo):
    output = run("gh", "api", "--paginate", "--jq",
                 '.[] | [.tag_name, (.prerelease|tostring), '
                 '([.assets[].name] | index("release-info.json") != null | tostring)] | @tsv',
                 "repos/" + repo + "/releases?per_page=100")
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) != 3:
            raise ValueError("Unexpected release response")
        yield fields[0], fields[1] == "true", fields[2] == "true"


def published_code(tag, has_metadata, repo):
    if has_metadata:
        with tempfile.TemporaryDirectory() as folder:
            run("gh", "release", "download", tag, "--repo", repo,
                "--pattern", "release-info.json", "--dir", folder)
            info = json.loads(Path(folder, "release-info.json").read_text())
        if info.get("schema") != 1 or info.get("versionName") != tag.removeprefix("v"):
            raise ValueError("Release metadata mismatch: " + tag)
        if info.get("applicationId") != "io.github.h3nb.jlmodplus":
            raise ValueError("Release package mismatch: " + tag)
        code = info.get("versionCode")
    else:
        if tag not in ("v0.1.0-alpha.1", "v0.1.0-dev.8.1.d09e75e"):
            raise ValueError("Release without metadata: " + tag)
        props = run("git", "show", tag + ":gradle.properties")
        match = re.search(r"^jlmod\.versionCode=(\d+)$", props, re.MULTILINE)
        if match is None:
            raise ValueError("Unknown legacy versionCode: " + tag)
        code = int(match.group(1))
    if type(code) is not int or code < 1 or code >= 2100000000:
        raise ValueError("Invalid versionCode: " + tag)
    return code


def allocate(version, branch, releases, code_for):
    if INPUT.fullmatch(version) is None:
        raise ValueError("Use X.Y.Z[-alpha|-beta|-rc] without v; use a new version for each publication")
    preview = "-" in version
    if (branch, preview) not in (("alpha", True), ("stable", False)):
        raise ValueError("Preview must use alpha; Stable must use stable (not legacy main)")
    highest = 0
    for tag, was_preview, has_metadata in releases:
        if tag == "v" + version:
            raise ValueError("The release version already exists")
        highest = max(highest, code_for(tag, has_metadata))
        previous = tag.removeprefix("v")
        if previous == "0.1.0-dev.8.1.d09e75e":
            if preview and compare(version, previous) <= 0:
                raise ValueError("Use 0.2.0-alpha or newer after legacy dev releases")
            continue
        if (preview or not was_preview) and compare(version, previous) <= 0:
            raise ValueError("Version must exceed " + previous)
    if highest >= 2099999999:
        raise ValueError("Android versionCode limit reached")
    return highest + 1, preview


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        code, preview = allocate(
            args.version, args.branch, list_releases(args.repo),
            lambda tag, metadata: published_code(tag, metadata, args.repo),
        )
    except (ValueError, subprocess.CalledProcessError, OSError, json.JSONDecodeError) as error:
        parser.exit(1, "Release preflight failed: " + str(error) + "\n")
    with open(args.output, "a", encoding="utf-8") as output:
        output.write("version_name=" + args.version + "\n")
        output.write("version_code=" + str(code) + "\n")
        output.write("tag=v" + args.version + "\n")
        output.write("prerelease=" + str(preview).lower() + "\n")
    print("Planned", args.version, "versionCode", code, "prerelease", preview)


if __name__ == "__main__":
    main()
