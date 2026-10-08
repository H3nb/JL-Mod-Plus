#!/usr/bin/env python3
"""Publish diagnostic evidence; timings are observations, never performance gates."""
import csv
import json
import os
from pathlib import Path
import statistics

root = Path("performance-artifacts")
samples = {}
environments = {}
frames = []
for label in ("baseline", "candidate"):
    directory = root / label
    environments[label] = json.loads((directory / "environment.json").read_text())
    cases = json.loads((directory / "search-cpu.json").read_text())
    samples[label] = {(case["rows"], case["sort"], case["query"]): case["threadCpuMs"]
                      for case in cases}
    for trace in sorted(directory.glob("*.perfetto-trace")):
        with Path(str(trace) + ".actions.csv").open() as stream:
            actions = list(csv.DictReader(stream))
        expected = ["BEGIN/" + trace.stem, "END/" + trace.stem]
        if [action["msg"] for action in actions] != expected:
            raise RuntimeError(f"{trace}: missing or unexpected action markers: {actions}")
        started = actions[0]["all_sources_started_ns"]
        if started in ("", "[NULL]") or int(actions[0]["ts"]) < int(started):
            raise RuntimeError(f"{trace}: action preceded data-source readiness")
        with Path(str(trace) + ".frames.csv").open() as stream:
            groups = list(csv.DictReader(stream))
        frames.append({"label": label, "trace": trace.stem,
                       "frames": sum(int(g["frames"]) for g in groups),
                       "appDeadlineMissed": sum(int(g["frames"]) for g in groups
                                               if "App Deadline Missed" in g["jank_type"]),
                       "dropped": sum(int(g["frames"]) for g in groups
                                      if "Dropped Frame" in g["jank_type"])})
if len(samples["baseline"]) != 8 or samples["baseline"].keys() != samples["candidate"].keys():
    raise RuntimeError("The eight A/B search cases did not all execute")
lines = ["Debug emulator diagnostics; 100 warm-up calls and 20 thread-CPU samples per case.",
         "Ordered A/B observations are not physical-device FPS or causal startup gains.",
         "", "| Rows | Sort | Query | Baseline median ms | Candidate median ms | Change |",
         "| --- | --- | --- | ---: | ---: | ---: |"]
comparisons = []
for key, old in samples["baseline"].items():
    new = samples["candidate"][key]
    if len(old) != 20 or len(new) != 20:
        raise RuntimeError(f"{key}: incomplete samples")
    before, after = statistics.median(old), statistics.median(new)
    change = (after / before - 1) * 100
    lines.append(f"| {key[0]} | {'Title' if key[1] == 0 else 'Date'} | {key[2]} | "
                 f"{before:.3f} | {after:.3f} | {change:+.1f}% |")
    comparisons.append({"rows": key[0], "sort": key[1], "query": key[2],
                        "baselineMs": before, "candidateMs": after,
                        "baselineSamples": old, "candidateSamples": new})
lines += ["", "| Revision | Trace | Frames | App deadline missed | Dropped |",
          "| --- | --- | ---: | ---: | ---: |"]
for row in frames:
    lines.append(f"| {row['label']} | {row['trace']} | {row['frames']} | "
                 f"{row['appDeadlineMissed']} | {row['dropped']} |")
text = "\n".join(lines) + "\n"
(root / "summary.md").write_text(text)
(root / "summary.json").write_text(json.dumps(
    {"environments": environments, "search": comparisons, "frames": frames}, indent=2))
print(text)
print("MEASUREMENT_JSON=" + (root / "summary.json").read_text())
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
        stream.write(text)
