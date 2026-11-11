# SPDX-License-Identifier: Apache-2.0
"""The quality comparator must reject missing evidence and distinguish strict/review policies."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "icon-quality" / "compare.py"
PNG = bytes.fromhex("89504e470d0a1a0a") + b"test-image" * 8


class IconQualityCompareTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for variant in ("baseline", "candidate"):
            directory = self.root / variant
            (directory / "images").mkdir(parents=True)
            record = {
                "id": "fixture-1",
                "inputWidth": 64,
                "inputHeight": 64,
                "enhance": True,
                "preserveArtwork": False,
                "slotAspectRatio": 1.0,
                "visual": {
                    "mode": "Subject",
                    "filter": "0",
                    "scale": 0.8,
                    "width": 32,
                    "height": 32,
                    "tileArgb": "ff123456",
                    "argbSha256": "a" * 64,
                    "slotContentScale": "Fit",
                    "slotPreviewSha256": "b" * 64,
                },
                "cpuNs": [1000000] * 45,
                "allocatedBytes": [80] * 45,
            }
            (directory / "metrics.json").write_text(
                json.dumps({"schema": 1, "cases": [record]}), encoding="utf-8"
            )
            for kind in ("source", "normalized", "slot"):
                (directory / "images" / ("fixture-1-" + kind + ".png")).write_bytes(PNG)

    def invoke(self, mode="strict", expected=None):
        command = [sys.executable, str(SCRIPT), "--root", str(self.root),
                   "--mode", mode, "--baseline-sha", "a" * 40,
                   "--candidate-sha", "b" * 40]
        if expected is not None:
            file = self.root / "expected.json"
            file.write_text(json.dumps(expected), encoding="utf-8")
            command.extend(["--expected-changes", str(file)])
        return subprocess.run(command, text=True, capture_output=True, check=False)

    def change_candidate_scale(self):
        name = self.root / "candidate" / "metrics.json"
        document = json.loads(name.read_text(encoding="utf-8"))
        document["cases"][0]["visual"]["scale"] = 0.7
        name.write_text(json.dumps(document), encoding="utf-8")

    def test_strict_identical_produces_report(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads((self.root / "report.json").read_text(encoding="utf-8"))
        self.assertEqual(0, report["visualChanges"])
        self.assertTrue((self.root / "index.html").is_file())

    def test_strict_rejects_visual_change_but_preserves_artifact(self):
        self.change_candidate_scale()
        result = self.invoke()
        self.assertEqual(1, result.returncode, result.stderr)
        report = json.loads((self.root / "report.json").read_text(encoding="utf-8"))
        self.assertEqual(["scale"], report["cases"][0]["visualDifferences"])

    def test_review_reports_change_without_failure(self):
        self.change_candidate_scale()
        self.assertEqual(0, self.invoke(mode="review").returncode)

    def test_expected_visual_difference_is_accepted_exactly(self):
        self.change_candidate_scale()
        result = self.invoke(expected={"fixture-1": {"scale": [0.8, 0.7]}})
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads((self.root / "report.json").read_text(encoding="utf-8"))
        self.assertEqual(1, report["visualChanges"])
        self.assertEqual(0, report["unexpectedChanges"])

    def test_unlisted_visual_difference_still_fails_with_expected_policy(self):
        self.change_candidate_scale()
        result = self.invoke(expected={"fixture-1": {"slotPreviewSha256": "changed"}})
        self.assertEqual(1, result.returncode)

    def test_expected_visual_difference_must_really_exist(self):
        result = self.invoke(expected={"fixture-1": {"scale": [0.8, 0.7]}})
        self.assertEqual(1, result.returncode)

    def test_missing_data_is_an_error_even_in_review_mode(self):
        (self.root / "candidate" / "metrics.json").unlink()
        self.assertEqual(2, self.invoke(mode="review").returncode)


if __name__ == "__main__":
    unittest.main()
