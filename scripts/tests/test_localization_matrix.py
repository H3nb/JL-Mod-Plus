# SPDX-License-Identifier: Apache-2.0
"""Matrix export contracts: source ownership, locale identity, and plural preservation."""

import json
from pathlib import Path
import tempfile
import unittest

from scripts import localization_audit, localization_matrix


class MatrixTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.res = self.root / "app/src/main/res"
        self.res.mkdir(parents=True)
        (self.res / "resources.properties").write_text("unqualifiedResLocale=en\n", encoding="utf-8")

    def xml(self, directory, content):
        path = self.res / directory / "strings.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("<resources>" + content + "</resources>", encoding="utf-8")

    def matrix(self, **kwargs):
        return localization_matrix.build_matrix(
            localization_audit.build_inventory(self.root), **kwargs
        )

    def test_missing_is_null_not_empty_and_invariants_are_excluded(self):
        self.xml("values", '<string name="search_hint">Search</string>'
                           '<string name="empty">Empty</string>'
                           '<string name="code" translatable="false">stable</string>')
        self.xml("values-in", '<string name="search_hint"></string>')
        result = self.matrix(locales=["id"])
        self.assertEqual(["empty", "search_hint"], [r["key"] for r in result["rows"]])
        self.assertEqual({"present": 1, "missing": 1}, result["coverage"]["id"])
        self.assertIsNone(result["rows"][0]["translations"]["id"])
        self.assertEqual("", result["rows"][1]["translations"]["id"]["text"])

    def test_plural_categories_are_preserved_not_forced_to_match_english(self):
        self.xml("values", '<plurals name="app_count"><item quantity="one">%1$d app</item>'
                           '<item quantity="other">%1$d apps</item></plurals>')
        self.xml("values-ru", '<plurals name="app_count"><item quantity="one">%1$d</item>'
                             '<item quantity="few">%1$d</item>'
                             '<item quantity="many">%1$d</item>'
                             '<item quantity="other">%1$d</item></plurals>')
        row = self.matrix(locales=["ru"])["rows"][0]
        self.assertEqual(["one", "other"],
                         [item["quantity"] for item in row["english"]["items"]])
        self.assertEqual(["one", "few", "many", "other"],
                         [item["quantity"] for item in row["translations"]["ru"]["items"]])

    def test_legacy_alias_region_and_qualified_overrides(self):
        self.xml("values", '<string name="a">A</string>')
        self.xml("values-in", '<string name="a">Indonesia</string>')
        self.xml("values-in-night", '<string name="a">Night</string>')
        self.xml("values-pt-rBR", '<string name="a">Brasil</string>')
        result = self.matrix(locales=["id", "pt-BR"])
        self.assertEqual("Indonesia", result["rows"][0]["translations"]["id"]["text"])
        self.assertEqual("Brasil", result["rows"][0]["translations"]["pt-BR"]["text"])
        self.assertEqual(["id", "pt-BR"], result["locales"])

    def test_prefix_and_key_file_selection(self):
        self.xml("values", '<string name="memory_editor_freeze">Freeze</string>'
                           '<string name="library_search">Search</string>')
        self.xml("values-in", '<string name="library_search">Cari</string>')
        result = self.matrix(prefixes=["memory_editor_"], locales=["id"])
        self.assertEqual(["memory_editor_freeze"], [row["key"] for row in result["rows"]])
        output = self.root / "out.json"
        key_file = self.root / "keys.txt"
        key_file.write_text("# Chosen context\nlibrary_search\n", encoding="utf-8")
        code = localization_matrix.main([
            "--repo-root", str(self.root), "--locale", "id",
            "--keys-file", str(key_file), "--output", str(output),
        ])
        self.assertEqual(0, code)
        self.assertEqual(["library_search"],
                         [row["key"] for row in json.loads(output.read_text())["rows"]])

    def test_invalid_selections_fail_without_writing_output(self):
        self.xml("values", '<string name="a">A</string>')
        self.xml("values-in", "")
        with self.assertRaisesRegex(ValueError, "Unknown locale"):
            self.matrix(locales=["xx"])
        with self.assertRaisesRegex(ValueError, "Unknown key"):
            self.matrix(keys=["missing"])
        with self.assertRaisesRegex(ValueError, "No translatable"):
            self.matrix(prefixes=["none_"])


if __name__ == "__main__":
    unittest.main()
