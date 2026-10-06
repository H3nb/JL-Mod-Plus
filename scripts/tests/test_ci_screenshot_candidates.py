# SPDX-License-Identifier: Apache-2.0

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("candidates", SCRIPTS / "ci-screenshot-candidates.py")
candidates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidates)

PNG = b"\x89PNG\r\n\x1a\nfixture"


class ScreenshotCandidatesTest(unittest.TestCase):
    def failure(self, failure_type):
        return ET.fromstring(f'<failure type="{failure_type}"/>')

    def write_png(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(PNG)

    def write_report(self, path, cases):
        root = ET.Element("testsuite")
        for case_spec in cases:
            case = ET.SubElement(
                root, "testcase",
                classname=case_spec.get("classname", "PreviewTest"),
                name=case_spec.get("name", "preview"),
            )
            ET.SubElement(case, case_spec.get("element", "failure"), type=case_spec["failure"])
            properties = ET.SubElement(case, "properties")
            for name, value in case_spec.get("properties", {}).items():
                ET.SubElement(properties, "property", name=name, value=str(value))
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        references = root / "references"
        build_root = root / "build"
        report = root / "report.xml"
        reference = references / "screen" / "preview.png"
        actual = build_root / "plugin-layout" / "rendered" / "screen" / "preview.png"
        diff = build_root / "another-layout" / "diffs" / "screen" / "preview.png"
        self.write_png(reference)
        self.write_png(actual)
        self.write_png(diff)
        return temporary, references, build_root, report, reference, actual, diff

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

    def test_valid_comparison_uses_report_paths_without_fixed_result_layout(self):
        temporary, references, build_root, report, reference, actual, diff = self.fixture()
        with temporary:
            self.write_report(report, [{
                "failure": "com.android.tools.screenshot.ImageComparisonAssertionError",
                "properties": {
                    "PreviewScreenshot.refImagePath": reference,
                    "PreviewScreenshot.newImagePath": actual,
                    "PreviewScreenshot.diffImagePath": diff,
                },
            }])
            found, excluded = candidates.collect_candidates(report, references, build_root)
            self.assertEqual([], excluded)
            self.assertEqual([Path("screen/preview.png")], list(found))
            self.assertEqual((reference, actual, diff), found[Path("screen/preview.png")])

    def test_renderer_or_compilation_failure_is_excluded_without_image_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "report.xml"
            self.write_report(report, [{"failure": "java.lang.IllegalStateException"}])
            found, excluded = candidates.collect_candidates(
                report, root / "references", root / "build"
            )
            self.assertEqual({}, found)
            self.assertEqual(["PreviewTest.preview"], excluded)

    def test_missing_xml_report_has_no_candidates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            found, excluded = candidates.collect_candidates(
                root / "missing.xml", root / "references", root / "build"
            )
            self.assertEqual({}, found)
            self.assertEqual([], excluded)

    def test_missing_screenshot_properties_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "report.xml"
            self.write_report(report, [{
                "failure": "com.android.tools.screenshot.ImageComparisonAssertionError",
            }])
            with self.assertRaisesRegex(ValueError, "Missing reference path"):
                candidates.collect_candidates(report, root / "references", root / "build")

    def test_mismatched_reference_and_result_paths_fail_closed(self):
        temporary, references, build_root, report, reference, actual, _ = self.fixture()
        with temporary:
            other = actual.with_name("other.png")
            self.write_png(other)
            self.write_report(report, [{
                "failure": "com.android.tools.screenshot.ImageComparisonAssertionError",
                "properties": {
                    "PreviewScreenshot.refImagePath": reference,
                    "PreviewScreenshot.newImagePath": other,
                },
            }])
            with self.assertRaisesRegex(ValueError, "Mismatched reference/result paths"):
                candidates.collect_candidates(report, references, build_root)

    def test_invalid_renderer_png_fails_closed(self):
        temporary, references, build_root, report, reference, actual, _ = self.fixture()
        with temporary:
            actual.write_bytes(b"not a png")
            self.write_report(report, [{
                "failure": "com.android.tools.screenshot.ImageComparisonAssertionError",
                "properties": {
                    "PreviewScreenshot.refImagePath": reference,
                    "PreviewScreenshot.newImagePath": actual,
                },
            }])
            with self.assertRaisesRegex(ValueError, "Invalid renderer result PNG"):
                candidates.collect_candidates(report, references, build_root)

    def test_duplicate_reference_mapping_fails_closed(self):
        temporary, references, build_root, report, reference, actual, _ = self.fixture()
        with temporary:
            properties = {
                "PreviewScreenshot.refImagePath": reference,
                "PreviewScreenshot.newImagePath": actual,
            }
            self.write_report(report, [
                {"name": "first", "failure": "com.android.tools.screenshot.ImageComparisonAssertionError", "properties": properties},
                {"name": "second", "failure": "com.android.tools.screenshot.ImageComparisonAssertionError", "properties": properties},
            ])
            with self.assertRaisesRegex(ValueError, "Duplicate reference mapping"):
                candidates.collect_candidates(report, references, build_root)

    def test_reference_tree_must_be_unchanged(self):
        with mock.patch.object(candidates, "git", return_value=b" M reference.png\n"):
            with self.assertRaisesRegex(RuntimeError, "Reference files must be unchanged"):
                candidates.ensure_references_unchanged(Path("references"))


    def test_main_reports_unavailable_once_when_report_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            with (
                mock.patch.object(candidates, "REPORT", Path(directory) / "missing.xml"),
                mock.patch.object(candidates, "report_output") as report_output,
            ):
                candidates.main()
        report_output.assert_called_once_with(False, 0)

    def test_main_reports_available_once_after_packaging(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            report.write_text("<testsuite/>", encoding="utf-8")
            found = {Path("screen/preview.png"): (Path("reference.png"), Path("actual.png"), None)}
            with (
                mock.patch.object(candidates, "REPORT", report),
                mock.patch.object(candidates, "ensure_references_unchanged"),
                mock.patch.object(candidates, "collect_candidates", return_value=(found, [])),
                mock.patch.object(candidates, "package_candidates") as package_candidates,
                mock.patch.object(candidates, "report_output") as report_output,
            ):
                candidates.main()
        package_candidates.assert_called_once_with(found, [])
        report_output.assert_called_once_with(True, 1)

    def test_main_passes_excluded_failures_to_packaging(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            report.write_text("<testsuite/>", encoding="utf-8")
            found = {Path("screen/preview.png"): (Path("reference.png"), Path("actual.png"), None)}
            excluded = ["PreviewTest.rendererFailure"]
            with (
                mock.patch.object(candidates, "REPORT", report),
                mock.patch.object(candidates, "ensure_references_unchanged"),
                mock.patch.object(candidates, "collect_candidates", return_value=(found, excluded)),
                mock.patch.object(candidates, "package_candidates") as package_candidates,
                mock.patch.object(candidates, "report_output"),
            ):
                candidates.main()
        package_candidates.assert_called_once_with(found, excluded)


if __name__ == "__main__":
    unittest.main()
