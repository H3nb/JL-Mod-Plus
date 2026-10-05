# SPDX-License-Identifier: Apache-2.0
"""Package comparison failures from the pinned screenshot plugin without re-rendering."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


REFERENCES = Path("app/src/screenshotTestEmulatorDebug/reference")
RESULTS = Path("app/build/outputs/screenshotTest-results/preview/debug/emulator")
REPORT = Path("app/build/test-results/validateEmulatorDebugScreenshotTest/TEST-preview-screenshot-test-engine.xml")
OUTPUT = Path("screenshot-candidates")
COMPARISON_FAILURES = {
    "com.android.tools.screenshot.differ.ImageComparisonAssertionError",
    "com.android.tools.screenshot.ImageComparisonAssertionError",
}

def is_comparison_failure(failures):
    return all(f.get("type") in COMPARISON_FAILURES for f in failures)


def git(*args, env=None):
    return subprocess.check_output(["git", *args], env=env)


def report_output(available, count):
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"available={str(available).lower()}\ncount={count}\n")
    print(f"Packaged {count} screenshot candidates; available={available}.")


def checked_path(value, directory):
    path = Path(value)
    relative = path.resolve().relative_to(directory.resolve())
    if path.suffix != ".png":
        raise ValueError(f"Expected PNG path: {path}")
    return path, relative


def main():
    report_output(False, 0)
    if not REPORT.is_file():
        print("No screenshot XML report; rendering/compilation failures need diagnosis.")
        return
    if git("status", "--porcelain", "--", str(REFERENCES)).strip():
        raise RuntimeError("Reference files must be unchanged before packaging candidates.")

    candidates = {}
    excluded = []
    for case in ET.parse(REPORT).getroot().iter("testcase"):
        failures = [*case.findall("failure"), *case.findall("error")]
        if not failures:
            continue
        name = f"{case.get('classname')}.{case.get('name')}"
        if not is_comparison_failure(failures):
            excluded.append(name)
            continue
        props = {p.get("name"): p.get("value") for p in case.findall("properties/property")}
        reference, relative = checked_path(props["PreviewScreenshot.refImagePath"], REFERENCES)
        actual, actual_relative = checked_path(props["PreviewScreenshot.newImagePath"], RESULTS / "rendered")
        if relative != actual_relative or not actual.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError(f"Invalid renderer output mapping: {name}")
        if relative in candidates:
            raise ValueError(f"Duplicate reference mapping: {relative}")
        diff = None
        if props.get("PreviewScreenshot.diffImagePath"):
            diff, diff_relative = checked_path(props["PreviewScreenshot.diffImagePath"], RESULTS / "diffs")
            if diff_relative != relative:
                raise ValueError(f"Invalid diff mapping: {name}")
        candidates[relative] = (reference, actual, diff)

    if excluded:
        print("Excluded non-comparison failures: " + ", ".join(excluded))
    if not candidates:
        return

    OUTPUT.mkdir(exist_ok=False)
    # A temporary Git index builds the patch without changing references or the real index.
    with tempfile.TemporaryDirectory() as temporary:
        environment = dict(os.environ, GIT_INDEX_FILE=str(Path(temporary) / "index"))
        git("read-tree", "HEAD", env=environment)
        for relative, (reference, actual, diff) in candidates.items():
            for kind, source in (("before", reference), ("after", actual), ("diff", diff)):
                if source is not None and source.is_file():
                    destination = OUTPUT / kind / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, destination)
            blob = git("hash-object", "-w", "--", str(actual)).decode().strip()
            git("update-index", "--add", "--cacheinfo", "100644", blob,
                (REFERENCES / relative).as_posix(), env=environment)
        (OUTPUT / "baselines.patch").write_bytes(git("diff", "--cached", "--binary", "HEAD", "--", str(REFERENCES), env=environment))
        (OUTPUT / "changes.txt").write_bytes(git("diff", "--cached", "--stat", "HEAD", "--", str(REFERENCES), env=environment))

    commit = git("rev-parse", "HEAD").decode().strip()
    (OUTPUT / "source-commit.txt").write_text(commit + "\n", encoding="utf-8")
    context = {
        "build_commit": commit,
        "pr_head_commit": os.environ.get("PR_HEAD_SHA", ""),
        "pr_base_commit": os.environ.get("PR_BASE_SHA", ""),
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "candidate_paths": [(REFERENCES / path).as_posix() for path in candidates],
        "excluded_failures": excluded,
        "note": "Review candidates before applying. No references were changed; validation still failed.",
    }
    (OUTPUT / "source-context.json").write_text(json.dumps(context, indent=2) + "\n", encoding="utf-8")
    report_output(True, len(candidates))


if __name__ == "__main__":
    main()
