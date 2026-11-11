# SPDX-License-Identifier: Apache-2.0
"""Summarize existing Gradle output without changing validation outcomes."""
import argparse
from collections import Counter
import os
from pathlib import Path
import re

TASK = re.compile(r"^> Task \S+(?: (FROM-CACHE|UP-TO-DATE|NO-SOURCE|SKIPPED|FAILED))?$")
RESULT = re.compile(r"^BUILD (SUCCESSFUL|FAILED) in (.+)$")
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def inspect_log(path):
    if not path.is_file():
        return "Not run (log unavailable)", Counter()
    counts = Counter()
    outcome = "Incomplete (no final Gradle result)"
    with path.open(encoding="utf-8", errors="replace") as log:
        for raw in log:
            line = ANSI.sub("", raw).strip()
            task = TASK.fullmatch(line)
            if task:
                counts[task.group(1) or "EXECUTED"] += 1
            result = RESULT.fullmatch(line)
            if result:
                outcome = f"{result.group(1)} in {result.group(2)}"
    return outcome, counts


def report(label, entries):
    lines = [
        f"### {label}: Gradle work",
        "",
        "| Invocation | Result | From cache | Up to date | Executed | Other |",
        "| --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for name, path in entries:
        outcome, counts = inspect_log(path)
        other = counts["NO-SOURCE"] + counts["SKIPPED"] + counts["FAILED"]
        lines.append(
            f"| {name} | {outcome} | {counts['FROM-CACHE']} | "
            f"{counts['UP-TO-DATE']} | {counts['EXECUTED']} | {other} |"
        )
    lines.extend([
        "",
        "Counts describe Gradle task lines, not distinct tasks or cache restore "
        "success. Durations are Gradle-reported, not total runner time. "
        "Check the job logs and HTML profiles for causes of misses and slow tasks.",
        "",
    ])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True)
    parser.add_argument("--log", action="append", required=True, metavar="NAME=PATH")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    entries = []
    for item in args.log:
        name, separator, path = item.partition("=")
        if not separator or not name or not path:
            parser.error("Each --log must be NAME=PATH")
        entries.append((name, Path(path)))
    summary = report(args.label, entries)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(summary, encoding="utf-8")
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
            out.write(summary)


if __name__ == "__main__":
    main()
