#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Report matching Android icon probes; image changes require human visual review."""
import argparse
import hashlib
import html
import json
import math
import os
from pathlib import Path
import statistics
import sys

VISUAL = ("width", "height", "mode", "filter", "scale", "tileArgb", "argbSha256")


def median(values):
    return statistics.median(values)


def p95(values):
    return sorted(values)[math.ceil(0.95 * len(values)) - 1]


def load(root, name):
    folder = root / name
    data = json.loads((folder / "metrics.json").read_text(encoding="utf-8"))
    if data.get("schema") != 1 or not isinstance(data.get("cases"), list) or not data["cases"]:
        raise ValueError("Invalid probe schema or empty cases for " + name)
    cases = {}
    for case in data["cases"]:
        ident = case["id"]
        if not ident or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in ident):
            raise ValueError("Invalid fixture identifier")
        if ident in cases:
            raise ValueError("Duplicate fixture " + ident)
        for k in VISUAL:
            if k not in case["visual"]:
                raise ValueError("Missing visual property " + k + " for " + ident)
        for key in ("cpuNs", "allocatedBytes"):
            values = case[key]
            if not isinstance(values, list) or any(type(v) is not int or v < 0 for v in values):
                raise ValueError("Malformed " + key + " for " + ident)
            if key == "cpuNs" and (len(values) < 20 or any(v == 0 for v in values)):
                raise ValueError("Missing CPU samples for " + ident)
        for suffix in ("source", "normalized"):
            png = folder / "images" / (ident + "-" + suffix + ".png")
            if not png.is_file() or png.stat().st_size <= 40:
                raise ValueError("Missing image " + str(png))
            with png.open("rb") as stream:
                if stream.read(8) != bytes.fromhex("89504e470d0a1a0a"):
                    raise ValueError("Invalid PNG " + str(png))
        cases[ident] = case
    return cases


def stats(values, divisor=1):
    return None if not values else {"n": len(values), "p50": median(values) / divisor,
                                    "p95": p95(values) / divisor}


def diff(before, after):
    return (after / before - 1) * 100 if before else None


def compare(root, mode, baseline_sha, candidate_sha):
    base = load(root, "baseline")
    head = load(root, "candidate")
    if base.keys() != head.keys():
        raise ValueError("Fixture identifiers/order differ")
    records = []
    md = [
        "## Enhanced Icons A/B benchmark",
        "",
        "Baseline SHA: " + baseline_sha + " · Candidate SHA: " + candidate_sha,
        "",
        "| Fixture | CPU P50 baseline ms | Candidate ms | Change % | Alloc P50 baseline → candidate bytes | Visual properties |",
        "| --- | ---: | ---: | ---: | ---: | --- |",
    ]
    gallery = [
        "<!doctype html><html lang='en'><meta charset='utf-8'>",
        "<meta name='viewport' content='width=device-width,initial-scale=1'>",
        "<title>Enhanced Icons A/B Gallery</title>",
        "<style>body{font:15px system-ui;max-width:1000px;margin:24px auto;padding:0 16px}",
        "section{border:1px solid #9996;padding:14px;margin:18px 0;border-radius:10px}",
        "figure{display:inline-block;margin:10px 18px 10px 0}figcaption{font-size:13px}",
        "img{width:144px;height:144px;object-fit:contain;border:1px solid #9995;background:#9992}</style>",
        "<h1>Enhanced Icons A/B Gallery</h1><p>Normalized bitmap previews, not Compose slot output. "
        "Visual differences require human judgment.</p>",
    ]
    for ident in base:
        a, b = base[ident], head[ident]
        for key in ("inputWidth", "inputHeight", "enhance", "preserveArtwork"):
            if a[key] != b[key]:
                raise ValueError("Input mismatch: " + ident + " / " + key)
        source_paths = [root / v / "images" / (ident + "-source.png") for v in ("baseline", "candidate")]
        if hashlib.sha256(source_paths[0].read_bytes()).digest() != hashlib.sha256(source_paths[1].read_bytes()).digest():
            raise ValueError("Source fixture differs: " + ident)
        changed = [k for k in VISUAL if a["visual"][k] != b["visual"][k]]
        cpu_a, cpu_b = stats(a["cpuNs"], 1_000_000), stats(b["cpuNs"], 1_000_000)
        alloc_a, alloc_b = stats(a["allocatedBytes"]), stats(b["allocatedBytes"])
        delta = diff(cpu_a["p50"], cpu_b["p50"])
        alloc_display = (
            str(round(alloc_a["p50"])) + " → " + str(round(alloc_b["p50"]))
            if alloc_a and alloc_b else "n/a"
        )
        records.append({
            "id": ident, "visualDifferences": changed,
            "baselineVisual": a["visual"], "candidateVisual": b["visual"],
            "cpuMs": {"baseline": cpu_a, "candidate": cpu_b, "deltaP50Percent": delta},
            "threadAllocBytes": {"baseline": alloc_a, "candidate": alloc_b},
        })
        md.append("| " + ident + " | " + format(cpu_a["p50"], ".3f") +
                  " | " + format(cpu_b["p50"], ".3f") + " | " +
                  (format(delta, "+.1f") if delta is not None else "n/a") +
                  " | " + alloc_display + " | " +
                  (", ".join(changed) or "identical") + " |")
        gallery.append("<section><h2>" + html.escape(ident) + "</h2><p>" +
                       html.escape(", ".join(changed) or "Identical") + "</p>")
        for variant, suffix, title in (
            ("baseline", "source", "Original input"),
            ("baseline", "normalized", "Baseline"),
            ("candidate", "normalized", "Candidate"),
        ):
            path = variant + "/images/" + ident + "-" + suffix + ".png"
            gallery.append("<figure><img src='" + html.escape(path) +
                           "' alt='" + html.escape(title) + "'><figcaption>" +
                           html.escape(title) + "</figcaption></figure>")
        gallery.append("</section>")
    visual_count = sum(bool(x["visualDifferences"]) for x in records)
    md.extend([
        "",
        "Visual differences: " + str(visual_count) + "/" + str(len(records)) +
        " · Parity mode: " + mode,
        "P95 and full samples, PNGs, and metadata are in the enhanced-icon-quality artifact.",
        "Performance is normalization thread CPU only (including reflection overhead), not FPS or PSS.",
        "This fixed-order emulator A/B is exploratory; it does not prove device-level gains.",
        "Different images are review signals, not automatic evidence of aesthetic improvement.",
        "",
    ])
    (root / "report.json").write_text(json.dumps({
        "schema": 1, "mode": mode, "baselineSha": baseline_sha,
        "candidateSha": candidate_sha, "visualChanges": visual_count, "cases": records
    }, indent=2) + "\n", encoding="utf-8")
    (root / "report.md").write_text("\n".join(md), encoding="utf-8")
    (root / "index.html").write_text("\n".join(gallery) + "</html>\n", encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write("\n".join(md))
    return 1 if mode == "strict" and visual_count else 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--root", required=True, type=Path)
    p.add_argument("--mode", choices=("strict", "review"), default="strict")
    p.add_argument("--baseline-sha", required=True)
    p.add_argument("--candidate-sha", required=True)
    args = p.parse_args()
    try:
        code = compare(args.root, args.mode, args.baseline_sha, args.candidate_sha)
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print("Invalid icon benchmark evidence: " + str(error), file=sys.stderr)
        return 2
    print((args.root / "report.md").read_text(encoding="utf-8"))
    if code:
        print("Strict pixel/presentation parity failed. Review the gallery.", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
