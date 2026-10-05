# SPDX-License-Identifier: Apache-2.0
"""Small synthetic fixtures protecting inventory boundaries and format identity."""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

from scripts import localization_audit as audit


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.res = self.root / "app/src/main/res"
        self.res.mkdir(parents=True)
        (self.res / "resources.properties").write_text("unqualifiedResLocale=en\n", encoding="utf-8")
        self.xml("values", "strings.xml", "")

    def xml(self, directory, file, content):
        target = self.res / directory / file
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("<resources>" + content + "</resources>", encoding="utf-8")
        return target

    def report(self):
        return audit.build_inventory(self.root)

    def locale(self, report, tag):
        return next(row for row in report["locales"] if row["locale"] == tag)

    def test_fragmented_source_and_invariants_are_not_array_coverage(self):
        self.xml("values", "strings.xml", '<string name="a">A</string><string name="machine" translatable="false">stable</string>')
        self.xml("values", "other.xml", '<plurals name="count"><item quantity="one">%1$d item</item><item quantity="other">%1$d items</item></plurals>')
        self.xml("values", "arrays.xml", '<string-array name="options"><item>@string/a</item><item>Literal</item></string-array><string-array name="codes" translatable="false"><item>x</item></string-array>')
        self.xml("values-in", "strings.xml", '<string name="a">A</string>')
        report = self.report()
        self.assertEqual(2, report["source"]["translatable_resource_count"])
        self.assertEqual(1, report["source"]["invariant_text_declaration_count"])
        self.assertEqual(1, report["source"]["invariant_array_declaration_count"])
        self.assertEqual(50.0, self.locale(report, "id")["coverage_percent"])
        config = next(c for c in report["configurations"] if c["directory"] == "values")
        self.assertEqual(2, config["catalog_file_count"])
        self.assertNotIn("app/src/main/res/values/arrays.xml", config["catalog_files"])
        options = next(r for r in report["resources"] if r["key"] == "options")
        self.assertTrue(options["contains_string_references"])
        self.assertTrue(options["contains_literal_text"])
        self.assertEqual(["string_reference", "literal"], [i["kind"] for i in options["items"]])

    def test_locale_alias_regions_scripts_and_non_locale_qualifiers(self):
        expected = {"values-in": "id", "values-iw": "he", "values-ji": "yi",
                    "values-sat": "sat", "values-de": "de", "values-pt-rBR": "pt-BR",
                    "values-zh-rCN": "zh-CN", "values-zh-rTW": "zh-TW",
                    "values-b+zh+Hans+CN": "zh-Hans-CN", "values-b+es+419": "es-419"}
        for directory, tag in expected.items():
            with self.subTest(directory=directory):
                parsed = audit.configuration(directory)
                self.assertEqual(tag, parsed["locale"])
                self.assertEqual("localized", parsed["role"])
        for directory in ("values-night", "values-v29", "values-car", "values-land"):
            with self.subTest(directory=directory):
                parsed = audit.configuration(directory)
                self.assertIsNone(parsed["locale"])
                self.assertEqual("configuration_override", parsed["role"])
        parsed = audit.configuration("values-mcc310-mnc004-b+sr+Latn+RS-night-v29")
        self.assertEqual("sr-Latn-RS", parsed["locale"])
        self.assertEqual(["mcc310", "mnc004", "night", "v29"], parsed["qualifiers"])
        with self.assertRaises(ValueError):
            audit.configuration("values-b+en+unsupported")

    def test_configuration_override_is_not_duplicate_or_base_coverage(self):
        self.xml("values", "strings.xml", '<string name="a">A</string><string name="b">B</string>')
        self.xml("values-night", "strings.xml", '<string name="a">Night</string>')
        self.xml("values-v29", "strings.xml", '<string name="a">API 29</string>')
        self.xml("values-de", "strings.xml", '<string name="a">A</string>')
        self.xml("values-de-night", "strings.xml", '<string name="b">Nacht</string>')
        report = self.report()
        self.assertEqual(1, len(report["locales"]))
        self.assertEqual(50.0, self.locale(report, "de")["coverage_percent"])
        self.assertEqual(["values-de-night"], self.locale(report, "de")["qualified_directories"])
        self.assertEqual(3, len(report["configuration_overrides"]))
        self.assertEqual([], report["findings"]["duplicates"])

    def test_empty_catalogs_missing_extra_and_type_mismatch(self):
        self.xml("values", "strings.xml", '<string name="a">A</string><plurals name="count"><item quantity="other">%d</item></plurals>')
        self.xml("values-de", "strings.xml", '<plurals name="a"><item quantity="other">A</item></plurals><string name="extra">Extra</string>')
        self.xml("values-ca", "strings.xml", "")
        report = self.report()
        de = self.locale(report, "de")
        self.assertEqual(2, de["missing_count"])
        self.assertEqual(2, de["extra_count"])
        self.assertEqual(0, de["matching_source_count"])
        self.assertEqual([{"type": "plurals", "key": "count"}, {"type": "string", "key": "a"}], de["missing"])
        self.assertEqual(0.0, self.locale(report, "ca")["coverage_percent"])
        mismatch = report["findings"]["type_mismatches"][0]
        self.assertEqual("a", mismatch["key"])
        self.assertEqual(["string"], mismatch["source_types"])
        self.assertEqual(["plurals"], mismatch["localized_types"])

    def test_plural_categories_need_not_mirror_english(self):
        self.xml("values", "plurals.xml", '<plurals name="n"><item quantity="one">%1$d</item><item quantity="other">%1$d</item></plurals>')
        self.xml("values-ru", "plurals.xml", '<plurals name="n"><item quantity="one">%1$d</item><item quantity="few">%1$d</item><item quantity="many">%1$d</item><item quantity="other">%1$d</item></plurals>')
        report = self.report()
        self.assertEqual(100.0, self.locale(report, "ru")["coverage_percent"])
        target = next(r for r in report["resources"] if r["directory"] == "values-ru")
        self.assertEqual(["few", "many", "one", "other"], target["quantities"])
        self.assertEqual([], report["findings"]["placeholder_findings"])
        self.assertEqual([{"index": 1, "types": ["integer"]}], target["items"][0]["placeholders"]["arguments"])

    def test_indexed_reordering_width_precision_and_repeated_argument(self):
        src = audit.placeholders("%1$s — %2$04d — %3$+.2f — %1$s %% %n")
        dst = audit.placeholders("%3$.3f %2$d %1$S")
        self.assertEqual(src["arguments"], dst["arguments"])
        self.assertFalse(src["unindexed_multi_argument"])
        self.assertEqual([], src["uncertainties"])
        self.xml("values", "strings.xml", '<string name="a">%1$s — %2$d</string>')
        self.xml("values-in", "strings.xml", '<string name="a">%2$d — %1$s</string>')
        self.assertEqual([], self.report()["findings"]["placeholder_findings"])

    def test_true_placeholder_mismatch_and_single_implicit_argument(self):
        self.xml("values", "strings.xml", '<string name="a">%1$s</string><string name="b">%1$d</string><string name="c">%1$s</string>')
        self.xml("values-in", "strings.xml", '<string name="a">%s</string><string name="b">%1$s</string><string name="c">%2$s</string>')
        findings = self.report()["findings"]["placeholder_findings"]
        self.assertEqual(["b", "c"], [f["key"] for f in findings])
        self.assertTrue(all(f["reason"] == "argument_mismatch" for f in findings))

    def test_unindexed_multiple_arguments_are_ambiguous(self):
        self.xml("values", "strings.xml", '<string name="a">%s %d</string>')
        self.xml("values-in", "strings.xml", '<string name="a">%d %s</string>')
        findings = self.report()["findings"]["placeholder_findings"]
        self.assertEqual("unindexed_multi_argument", findings[0]["reason"])
        # Ordinary numbering is independent of explicit indices; relative reuse
        # refers to the previous argument, including one explicitly indexed.
        parsed = audit.placeholders("%2$s %s %<s")
        self.assertEqual([2, 1, 1], [t["index"] for t in parsed["tokens"]])
        self.assertTrue(parsed["unindexed_multi_argument"])
        self.assertEqual("reuse_without_argument", audit.placeholders("%<s")["uncertainties"][0]["reason"])

    def test_percent_literals_disabled_formatting_references_and_inline_markup(self):
        self.assertEqual([], audit.placeholders("%% %n")["arguments"])
        self.assertEqual([], audit.placeholders("%% %n")["uncertainties"])
        self.xml("values", "strings.xml", '<string name="literal">Scale (%)</string><string name="off" formatted="false">%1$s</string><string name="ref">@string/off</string><string name="bold"><b>%1$s</b></string>')
        self.xml("values-in", "strings.xml", '<string name="literal">Skala (%)</string><string name="off">Text</string><string name="ref">%1$s</string><string name="bold">%s</string>')
        report = self.report()
        reasons = {f["key"]: f["reason"] for f in report["findings"]["placeholder_findings"]}
        self.assertEqual({"literal": "unparsed_format", "off": "formatting_disabled", "ref": "resource_reference"}, reasons)

    def test_invariant_locale_overrides_are_inventory_only(self):
        self.xml("values", "strings.xml", '<string name="name" translatable="false">Product</string><string name="label">Label</string>')
        self.xml("values-in", "strings.xml", '<string name="name">Product</string><string name="label">Label</string>')
        report = self.report()
        self.assertEqual(100.0, self.locale(report, "id")["coverage_percent"])
        self.assertEqual(0, self.locale(report, "id")["extra_count"])
        finding = report["findings"]["localized_invariant_declarations"][0]
        self.assertEqual("name", finding["key"])

    def test_same_name_different_types_are_distinct_resources(self):
        self.xml("values", "strings.xml", '<string name="a">A</string><plurals name="a"><item quantity="other">%1$d</item></plurals>')
        self.xml("values-in", "strings.xml", '<string name="a">A</string>')
        report = self.report()
        self.assertEqual(2, report["source"]["translatable_resource_count"])
        self.assertEqual(50.0, self.locale(report, "id")["coverage_percent"])
        self.assertEqual([], report["findings"]["duplicates"])
        self.assertEqual([], report["findings"]["type_mismatches"])

    def test_duplicates_include_same_file_and_item_alias_declarations(self):
        self.xml("values", "strings.xml", '<string name="a">%1$s</string><item type="string" name="a">Again</item>')
        self.xml("values", "other.xml", '<string name="a">Third</string>')
        self.xml("values-in", "strings.xml", '<string name="a">%1$s</string>')
        report = self.report()
        duplicates = report["findings"]["duplicates"]
        self.assertEqual(1, len(duplicates))
        self.assertEqual(3, len(duplicates[0]["declarations"]))
        self.assertEqual(1, report["source"]["translatable_resource_count"])
        self.assertEqual("duplicate_declaration", report["findings"]["placeholder_findings"][0]["reason"])

    def test_cli_report_only_deterministic_output_and_no_input_mutation(self):
        self.xml("values", "strings.xml", '<string name="a">%1$s</string>')
        self.xml("values-de", "strings.xml", '<string name="a">%1$d</string><string name="extra">Extra</string>')
        self.xml("values-ca", "strings.xml", "")
        before = {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.res.rglob("*") if p.is_file()}
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, audit.main(["--repo-root", str(self.root)]))
        output = self.root / "build/reports/localization"
        initial = {p.name: p.read_bytes() for p in output.iterdir()}
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, audit.main(["--repo-root", str(self.root)]))
        self.assertEqual(initial, {p.name: p.read_bytes() for p in output.iterdir()})
        self.assertEqual(before, {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.res.rglob("*") if p.is_file()})
        report = json.loads(initial["inventory.json"])
        self.assertEqual(1, report["schema_version"])
        self.assertNotIn(str(self.root), initial["inventory.json"].decode("utf-8"))

    def test_untrustworthy_input_and_invalid_usage_fail(self):
        path = self.res / "values/strings.xml"
        path.write_text("<resources><string", encoding="utf-8")
        with redirect_stderr(io.StringIO()):
            self.assertEqual(1, audit.main(["--repo-root", str(self.root)]))
        self.assertFalse((self.root / "build").exists())
        path.unlink()
        (self.res / "resources.properties").unlink()
        with redirect_stderr(io.StringIO()):
            self.assertEqual(1, audit.main(["--repo-root", str(self.root)]))
            with self.assertRaises(SystemExit) as raised:
                audit.main(["--unknown"])
        self.assertEqual(2, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
