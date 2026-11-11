# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "ci_gradle_summary", Path(__file__).resolve().parents[1] / "ci-gradle-summary.py"
)
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class CIGradleSummaryTest(unittest.TestCase):
    def test_counts_gradle_task_states_and_final_result(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "gradle.log"
            log.write_text(
                "> Task :app:compileDebugKotlin FROM-CACHE\n"
                "> Task :app:compileDebugJava UP-TO-DATE\n"
                "> Task :app:assembleDebug\n"
                "> Task :app:prepareDebug NO-SOURCE\n"
                "> Task :app:testDebug SKIPPED\n"
                "BUILD SUCCESSFUL in 2m 5s\n",
                encoding="utf-8",
            )
            result, counts = summary.inspect_log(log)
            self.assertEqual("SUCCESSFUL in 2m 5s", result)
            self.assertEqual(1, counts["FROM-CACHE"])
            self.assertEqual(1, counts["UP-TO-DATE"])
            self.assertEqual(1, counts["EXECUTED"])
            self.assertEqual(1, counts["NO-SOURCE"])
            self.assertEqual(1, counts["SKIPPED"])
            self.assertIn("2m 5s", summary.report("Build", [("APK", log)]))

    def test_missing_and_interrupted_gradle_logs_remain_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            interrupted = root / "interrupted.log"
            interrupted.write_text("> Task :app:compileDebugKotlin FAILED\n", encoding="utf-8")
            result, counts = summary.inspect_log(interrupted)
            self.assertTrue(result.startswith("Incomplete"))
            self.assertEqual(1, counts["FAILED"])
            missing, counts = summary.inspect_log(root / "missing.log")
            self.assertTrue(missing.startswith("Not run"))
            self.assertFalse(counts)
            rendered = summary.report("Screenshot", [("Screenshot", root / "missing.log")])
            self.assertIn("Not run", rendered)

    def test_failed_build_result_is_reported_without_claiming_success(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "failed.log"
            log.write_text("BUILD FAILED in 41s\n", encoding="utf-8")
            result, counts = summary.inspect_log(log)
            self.assertEqual("FAILED in 41s", result)
            self.assertFalse(counts)


if __name__ == "__main__":
    unittest.main()
