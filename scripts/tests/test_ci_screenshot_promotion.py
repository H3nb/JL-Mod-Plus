# SPDX-License-Identifier: Apache-2.0

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("promotion", SCRIPTS / "ci-screenshot-promotion.py")
promotion = importlib.util.module_from_spec(spec)
spec.loader.exec_module(promotion)


class ScreenshotPromotionTest(unittest.TestCase):
    def setUp(self):
        self.path = promotion.REFERENCE_PREFIX + "suite/example.png"
        self.request = {
            "head_sha": "1" * 40,
            "base_sha": "2" * 40,
            "build_sha": "3" * 40,
            "run_id": 10,
            "artifact_id": 20,
            "artifact_digest": "sha256:" + "4" * 64,
        }
        self.before = promotion.PNG + b"before"
        self.after = promotion.PNG + b"after"

    def command(self, payload=None):
        return promotion.COMMAND + "\n" + json.dumps(payload or self.request)

    def write_bundle(self, target, *, context_change=None, extra=None):
        context = {
            "build_commit": self.request["build_sha"],
            "pr_head_commit": self.request["head_sha"],
            "pr_base_commit": self.request["base_sha"],
            "run_id": str(self.request["run_id"]),
            "candidate_paths": [self.path],
            "excluded_failures": [],
        }
        if context_change:
            context_change(context)
        relative = self.path[len(promotion.REFERENCE_PREFIX):]
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr("source-context.json", json.dumps(context))
            archive.writestr(f"before/{relative}", self.before)
            archive.writestr(f"after/{relative}", self.after)
            archive.writestr("baselines.patch", b"binary patch placeholder")
            for name, data in extra or []:
                archive.writestr(name, data)

    def test_parse_request_accepts_exact_contract(self):
        self.assertEqual(self.request, promotion.parse_request(self.command()))

    def test_parse_request_rejects_extra_key(self):
        payload = dict(self.request, unexpected=True)
        with self.assertRaises(promotion.Reject):
            promotion.parse_request(self.command(payload))

    def test_safe_bundle_accepts_exact_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "bundle.zip"
            self.write_bundle(archive)
            paths, files, patch = promotion.safe_bundle(archive, self.request)
            self.assertEqual([self.path], paths)
            self.assertEqual(self.after, files[0][2])
            self.assertTrue(patch)

    def test_safe_bundle_rejects_stale_head(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "bundle.zip"
            self.write_bundle(archive, context_change=lambda value: value.update(pr_head_commit="5" * 40))
            with self.assertRaises(promotion.Reject):
                promotion.safe_bundle(archive, self.request)

    def test_safe_bundle_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "bundle.zip"
            self.write_bundle(archive, extra=[("../escape.png", self.after)])
            with self.assertRaises(promotion.Reject):
                promotion.safe_bundle(archive, self.request)


if __name__ == "__main__":
    unittest.main()
