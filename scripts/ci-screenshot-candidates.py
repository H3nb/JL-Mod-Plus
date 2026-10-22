# SPDX-License-Identifier: Apache-2.0
"""Package genuine screenshot comparison failures without changing references."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


REFERENCES = Path("app/src/screenshotTestDefaultEmulatorDebug/reference")
BUILD_ROOT = Path("app/build")
REPORT = Path("app/build/test-results/testEmulatorDebugScreenshotTestDefaultTestSuite")
OUTPUT = Path("screenshot-candidates")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
COMPARISON_FAILURES = {
    "com.android.tools.screenshot.differ.ImageComparisonAssertionError",
    "com.android.tools.screenshot.ImageComparisonAssertionError",
}


def is_comparison_failure(failures):
    return bool(failures) and all(f.get("type") in COMPARISON_FAILURES for f in failures)


def git(*args, env=None):
    return subprocess.check_output(["git", *args], env=env)


def report_output(available, count):
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"available={str(available).lower()}\ncount={count}\n")
    print(f"Packaged {count} screenshot candidates; available={available}.")


def checked_png_path(value, directory, label):
    if not value:
        raise ValueError(f"Missing {label} path in screenshot report")
    path = Path(value)
    try:
        relative = path.resolve().relative_to(directory.resolve())
    except ValueError as error:
        raise ValueError(f"{label} path escapes {directory}: {path}") from error
    if path.suffix.lower() != ".png":
        raise ValueError(f"Expected PNG {label} path: {path}")
    return path, relative


def has_relative_suffix(path, expected):
    return len(path.parts) >= len(expected.parts) and path.parts[-len(expected.parts):] == expected.parts


def checked_png_file(path, label):
    if not path.is_file():
        raise ValueError(f"Missing {label} PNG: {path}")
    if not path.read_bytes().startswith(PNG_SIGNATURE):
        raise ValueError(f"Invalid {label} PNG: {path}")


def collect_candidates(report=REPORT, references=REFERENCES, build_root=BUILD_ROOT):
    reports = [report] if report.is_file() else sorted(report.glob("TEST-*.xml"))
    if not reports:
        return {}, []

    candidates = {}
    excluded = []
    cases = [case for xml in reports for case in ET.parse(xml).getroot().iter("testcase")]
    for case in cases:
        failures = [*case.findall("failure"), *case.findall("error")]
        if case.find("skipped") is not None:
            excluded.append(f"{case.get('classname')}.{case.get('name')} (skipped)")
            continue
        if not failures:
            continue
        name = f"{case.get('classname')}.{case.get('name')}"
        if not is_comparison_failure(failures):
            excluded.append(name)
            continue

        props = {p.get("name"): p.get("value") for p in case.findall("properties/property")}
        reference, relative = checked_png_path(
            props.get("PreviewScreenshot.refImagePath"), references, "reference"
        )
        actual, actual_relative = checked_png_path(
            props.get("PreviewScreenshot.newImagePath"), build_root, "renderer result"
        )
        checked_png_file(reference, "reference")
        checked_png_file(actual, "renderer result")
        if not has_relative_suffix(actual_relative, relative):
            raise ValueError(f"Mismatched reference/result paths: {name}")

        diff = None
        if props.get("PreviewScreenshot.diffImagePath"):
            diff, diff_relative = checked_png_path(
                props["PreviewScreenshot.diffImagePath"], build_root, "diff"
            )
            checked_png_file(diff, "diff")
            if not has_relative_suffix(diff_relative, relative):
                raise ValueError(f"Mismatched reference/diff paths: {name}")

        if relative in candidates:
            raise ValueError(f"Duplicate reference mapping: {relative}")
        candidates[relative] = (reference, actual, diff)

    return candidates, excluded


def ensure_references_unchanged(references=REFERENCES):
    if git("status", "--porcelain", "--", str(references)).strip():
        raise RuntimeError("Reference files must be unchanged before packaging candidates.")


def package_candidates(candidates, excluded, references=REFERENCES, output=OUTPUT, context=None):
    output.mkdir(exist_ok=False)
    with tempfile.TemporaryDirectory() as temporary:
        environment = dict(os.environ, GIT_INDEX_FILE=str(Path(temporary) / "index"))
        git("read-tree", "HEAD", env=environment)
        for relative in sorted(candidates, key=lambda path: path.as_posix()):
            reference, actual, diff = candidates[relative]
            for kind, source in (("before", reference), ("after", actual), ("diff", diff)):
                if source is not None:
                    destination = output / kind / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, destination)
            if actual is None:
                git("update-index", "--force-remove", "--", (references / relative).as_posix(), env=environment)
            else:
                blob = git("hash-object", "-w", "--", str(actual)).decode().strip()
                git(
                    "update-index", "--add", "--cacheinfo", "100644", blob,
                    (references / relative).as_posix(), env=environment
                )

        (output / "baselines.patch").write_bytes(
            git("diff", "--cached", "--binary", "HEAD", "--", str(references), env=environment)
        )
        (output / "changes.txt").write_bytes(
            git("diff", "--cached", "--stat", "HEAD", "--", str(references), env=environment)
        )

    commit = git("rev-parse", "HEAD").decode().strip()
    (output / "source-commit.txt").write_text(commit + "\n", encoding="utf-8")
    provenance = context or {}
    context = {
        "schema_version": 2,
        "generation": "manual" if provenance else "comparison",
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        "build_commit": commit,
        "pr_head_commit": provenance.get("pr_head_commit", os.environ.get("PR_HEAD_SHA", "")),
        "pr_base_commit": provenance.get("pr_base_commit", os.environ.get("PR_BASE_SHA", "")),
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "pr_number": provenance.get("pr_number", os.environ.get("PR_NUMBER", "")),
        "candidate_paths": [
            (references / path).as_posix()
            for path in sorted(candidates, key=lambda item: item.as_posix())
        ],
        "changes": [
            {
                "path": (references / path).as_posix(),
                "operation": "add" if before is None else "delete" if after is None else "replace",
                "before_sha256": hashlib.sha256(before.read_bytes()).hexdigest() if before else None,
                "after_sha256": hashlib.sha256(after.read_bytes()).hexdigest() if after else None,
            }
            for path, (before, after, _) in sorted(candidates.items())
        ],
        # Promotion must fail closed if the same screenshot run also contained renderer,
        # compilation, or missing-reference failures that were not packaged as comparisons.
        "excluded_failures": list(excluded),
        "note": "Review exact before/after images before applying; generation is not validation.",
    }
    (output / "source-context.json").write_text(
        json.dumps(context, indent=2) + "\n", encoding="utf-8"
    )


def main():
    if not REPORT.exists():
        print("No screenshot XML report; rendering/compilation failures need diagnosis.")
        report_output(False, 0)
        return

    ensure_references_unchanged()
    candidates, excluded = collect_candidates()
    if excluded:
        print("Excluded non-comparison failures: " + ", ".join(excluded))
    if not candidates:
        report_output(False, 0)
        return

    package_candidates(candidates, excluded)
    report_output(True, len(candidates))


def prepare_update(pr_number, destination):
    if not pr_number.isdecimal() or int(pr_number) < 1:
        raise ValueError("Manual screenshot generation requires an open PR number")
    repo = os.environ["GITHUB_REPOSITORY"]
    pr = json.loads(subprocess.check_output(["gh", "api", f"repos/{repo}/pulls/{pr_number}"]))
    head = git("rev-parse", "HEAD").decode().strip()
    if (pr["state"] != "open" or pr["head"]["repo"]["full_name"] != repo or pr["head"]["sha"] != head):
        raise ValueError("Select the exact branch HEAD of an open same-repository PR")
    ensure_references_unchanged()
    destination.write_text(json.dumps({
        "pr_number": str(pr["number"]), "pr_head_commit": head,
        "pr_base_commit": pr["base"]["sha"],
    }), encoding="utf-8")


def package_updated(context_path):
    context = json.loads(context_path.read_text(encoding="utf-8"))
    if git("rev-parse", "HEAD").decode().strip() != context["pr_head_commit"]:
        raise ValueError("HEAD moved during screenshot generation")
    tracked = git("ls-tree", "-r", "--name-only", "-z", "HEAD", "--", str(REFERENCES))
    before_paths = {Path(name.decode()).relative_to(REFERENCES) for name in tracked.split(b"\0") if name}
    after_paths = {path.relative_to(REFERENCES) for path in REFERENCES.rglob("*.png")}
    candidates = {}
    with tempfile.TemporaryDirectory() as directory:
        for relative in sorted(before_paths | after_paths):
            before = git("show", f"HEAD:{REFERENCES / relative}") if relative in before_paths else None
            after_path = REFERENCES / relative
            if relative in after_paths:
                checked_png_path(str(after_path), REFERENCES, "updated reference")
            after = after_path.read_bytes() if relative in after_paths else None
            if before == after:
                continue
            if relative.suffix != ".png" or any(data is not None and not data.startswith(PNG_SIGNATURE) for data in (before, after)):
                raise ValueError(f"Invalid changed reference: {relative}")
            before_path = Path(directory) / relative if before is not None else None
            if before_path:
                before_path.parent.mkdir(parents=True, exist_ok=True)
                before_path.write_bytes(before)
            candidates[relative] = (before_path, after_path if after is not None else None, None)
        if candidates:
            package_candidates(candidates, [], context=context)
    report_output(bool(candidates), len(candidates))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-update", metavar="PR_NUMBER")
    parser.add_argument("--updated", action="store_true")
    parser.add_argument("--context", type=Path, default=Path("screenshot-context.json"))
    args = parser.parse_args()
    if args.prepare_update is not None:
        prepare_update(args.prepare_update, args.context)
    elif args.updated:
        package_updated(args.context)
    else:
        main()
