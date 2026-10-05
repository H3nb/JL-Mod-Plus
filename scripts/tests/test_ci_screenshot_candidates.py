# SPDX-License-Identifier: Apache-2.0

import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("candidates", SCRIPTS / "ci-screenshot-candidates.py")
candidates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidates)


class ScreenshotCandidatesTest(unittest.TestCase):
    def failure(self, failure_type):
        return ET.fromstring(f'<failure type="{failure_type}"/>')

    def test_alpha16_comparison_failure_is_recognized(self):
        self.assertTrue(candidates.is_comparison_failure([
            self.failure("com.android.tools.screenshot.ImageComparisonAssertionError"),
        ]))

    def test_alpha15_comparison_failure_remains_recognized(self):
        self.assertTrue(candidates.is_comparison_failure([
            self.failure("com.android.tools.screenshot.differ.ImageComparisonAssertionError"),
        ]))

    def test_non_comparison_failure_is_rejected(self):
        self.assertFalse(candidates.is_comparison_failure([
            self.failure("java.lang.AssertionError"),
        ]))


if __name__ == "__main__":
    unittest.main()
