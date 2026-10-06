# SPDX-License-Identifier: Apache-2.0
"""Package genuine screenshot comparison failures without changing references."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


REFERENCES = Path("app/src/screenshotTestEmulatorDebug/reference")
BUILD_ROOT = Path("app/build")
REPORT = Path("app/build/test-results/validateEmulatorDebugScreenshotTest/TEST-preview-screenshot-test-engine.xml")
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
    if not report.is_file():
        return {}, []

    candidates = {}
    excluded = []
    for case in ET.parse(report).getroot().iter("testcase"):
        failures = [*case.findall("failure"), *case.findall("error")]
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


def package_candidates(candidates, references=REFERENCES, output=OUTPUT):
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
    context = {
        "build_commit": commit,
        "pr_head_commit": os.environ.get("PR_HEAD_SHA", ""),
        "pr_base_commit": os.environ.get("PR_BASE_SHA", ""),
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "candidate_paths": [
            (references / path).as_posix()
            for path in sorted(candidates, key=lambda item: item.as_posix())
        ],
        "note": "Review candidates before applying. No references were changed; validation still failed.",
    }
    (output / "source-context.json").write_text(
        json.dumps(context, indent=2) + "\n", encoding="utf-8"
    )


def main():
    if not REPORT.is_file():
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

    package_candidates(candidates)
    report_output(True, len(candidates))


if __name__ == "__main__":
    main()
