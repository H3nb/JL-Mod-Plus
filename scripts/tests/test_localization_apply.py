# SPDX-License-Identifier: Apache-2.0
"""Protect accepted batch edits against stale input and XML/resource corruption."""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import localization_apply as apply


class ApplyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.res = self.root / "app/src/main/res"
        self.res.mkdir(parents=True)
        (self.res / "resources.properties").write_text("unqualifiedResLocale=en\n")
        self.source = self.xml("values", '<string name="label">Label</string>')
        self.target = self.xml("values-in", "")

    def xml(self, directory, text):
        path = self.res / directory / "strings.xml"
        path.parent.mkdir(exist_ok=True)
        path.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + text + '\n</resources>\n', encoding="utf-8")
        return path

    def batch(self, strings=None, plurals=None, revisions=None):
        strings, plurals = strings or {}, plurals or {}
        return {"schema_version": 1, "locale": "id",
                "source_sha256": apply.sha256(self.source.read_bytes()),
                "target_sha256": apply.sha256(self.target.read_bytes()),
                "strings": strings, "plurals": plurals, "revisions": revisions or {},
                "reviewed_keys": list(strings) + list(plurals), "unresolved": []}

    def test_add_revise_escape_preserve_bytes_and_idempotence(self):
        self.source = self.xml("values", '<string name="label">Label</string>\n<string name="note" formatted="false">Note</string>')
        untouched = '    <!-- retained -->\n    <string name="label" product="default">Lama</string>\n    <string name="extra">Untouched &amp; text</string>'
        self.target = self.xml("values-in", untouched)
        batch = self.batch({"label": 'Baru & "teks"', "note": "@literal\nC:\\path isn't ?"}, revisions={"label": "Correct meaning"})
        original = self.target.read_bytes()
        dry = apply.apply_batch(self.root, batch)
        self.assertTrue(dry["changed"])
        self.assertEqual(original, self.target.read_bytes())
        self.assertTrue(apply.apply_batch(self.root, batch, True)["applied"])
        after = self.target.read_bytes()
        self.assertIn(b'<!-- retained -->', after)
        self.assertIn(b'<string name="label" product="default">Baru &amp; \\"teks\\"</string>', after)
        self.assertIn(b'<string name="extra">Untouched &amp; text</string>', after)
        self.assertIn(b'<string name="note" formatted="false">\\@literal\\nC:\\\\path isn\\\'t ?</string>', after)
        self.assertFalse(apply.apply_batch(self.root, batch, True)["changed"])
        self.assertEqual(after, self.target.read_bytes())

    def test_plural_categories_preserve_existing_item_attributes_and_comments(self):
        self.source = self.xml("values", '<plurals name="count"><item quantity="one">%1$d item</item><item quantity="other">%1$d items</item></plurals>')
        for newline in (b"\n", b"\r\n"):
            with self.subTest(newline=newline):
                self.target = self.xml("values-in", '<plurals name="count">\n<!-- retain plural comment -->\n<item quantity="other" product="default">%1$d lama</item>\n    </plurals>')
                fixture = self.target.read_bytes().replace(b"\r\n", b"\n")
                self.target.write_bytes(fixture.replace(b"\n", newline))
                batch = self.batch(plurals={"count": {"one": "%1$d benda", "other": "%1$d benda"}}, revisions={"count": "Correct noun"})
                apply.apply_batch(self.root, batch, True)
                data = self.target.read_bytes()
                self.assertIn(b'<!-- retain plural comment -->', data)
                self.assertIn(b'<item quantity="other" product="default">%1$d benda</item>', data)
                self.assertIn(b'<item quantity="one">%1$d benda</item>' + newline + b'    </plurals>', data)
                self.assertNotIn(newline + b'    ' + newline, data)
                self.assertFalse(apply.apply_batch(self.root, batch, True)["changed"])

    def test_source_and_target_hashes_prevent_stale_writes(self):
        batch = self.batch({"label": "Baru"})
        self.source.write_bytes(self.source.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "Stale canonical"):
            apply.apply_batch(self.root, batch, True)
        batch = self.batch({"label": "Baru"})
        self.target.write_bytes(self.target.read_bytes() + b"\n")
        before = self.target.read_bytes()
        with self.assertRaisesRegex(ValueError, "Stale target"):
            apply.apply_batch(self.root, batch, True)
        self.assertEqual(before, self.target.read_bytes())

    def test_invalid_changes_do_not_partially_apply_valid_ones(self):
        self.source = self.xml("values", '<string name="label">Label</string><string name="value">%1$d</string><string name="fixed" translatable="false">Fixed</string>')
        for invalid, pattern in [({"value": "%1$s"}, "argument mismatch"),
                                 ({"fixed": "Tetap"}, "invariant"),
                                 ({"unknown": "X"}, "Unknown")]:
            batch = self.batch({"label": "Baru", **invalid})
            before = self.target.read_bytes()
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, pattern):
                apply.apply_batch(self.root, batch, True)
            self.assertEqual(before, self.target.read_bytes())

    def test_rejects_unreviewed_unresolved_and_unexplained_revisions(self):
        self.target = self.xml("values-in", '<string name="label">Lama</string>')
        for field, value, pattern in [("reviewed_keys", [], "reviewed"),
                                      ("unresolved", [{"key": "label", "reason": "Missing context"}], "resolved"),
                                      ("revisions", {}, "requires a reason")]:
            batch = self.batch({"label": "Baru"}, revisions={"label": "Wrong word"})
            batch[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, pattern):
                apply.apply_batch(self.root, batch, True)

    def test_rejects_duplicate_resources_type_drift_markup_and_references(self):
        cases = [('<string name="label">A</string><string name="label">B</string>', "Duplicate"),
                 ('<plurals name="label"><item quantity="other">X</item></plurals>', "type mismatch"),
                 ('<string name="label"><b>A</b></string>', "Unsupported"),
                 ('<string name="label">@string/other</string>', "Unsupported"),
                 ('<string name="label" formatted="false">A</string>', "attribute contract")]
        for text, pattern in cases:
            self.target = self.xml("values-in", text)
            before = self.target.read_bytes()
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, pattern):
                apply.apply_batch(self.root, self.batch({"label": "Baru"}, revisions={"label": "Wrong meaning"}), True)
            self.assertEqual(before, self.target.read_bytes())

    def test_plural_invalid_categories_and_argument_contracts(self):
        self.source = self.xml("values", '<plurals name="count"><item quantity="other">%1$d items</item></plurals>')
        for text, pattern in [({}, "Invalid plural"), ({"one": "%1$d"}, "Invalid plural"),
                              ({"other": "%1$d", "invalid": "%1$d"}, "Invalid plural"),
                              ({"other": "%2$d"}, "argument mismatch")]:
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, pattern):
                apply.apply_batch(self.root, self.batch(plurals={"count": text}), True)

    def test_cli_duplicate_json_fields_and_control_characters_fail_without_writes(self):
        batch = self.batch({"label": "Bad\x00value"})
        with self.assertRaisesRegex(ValueError, "control characters"):
            apply.apply_batch(self.root, batch, True)
        path = self.root / "batch.json"
        path.write_text('{"schema_version":1,"schema_version":1}')
        with redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()):
            self.assertEqual(1, apply.main([str(path), "--repo-root", str(self.root), "--apply"]))

    def test_non_text_value_rejected_without_partial_write(self):
        self.source = self.xml("values", '<string name="label">Label</string><string name="count">%1$d</string>')
        before = self.target.read_bytes()
        with self.assertRaisesRegex(ValueError, "plain Unicode text"):
            apply.apply_batch(self.root, self.batch({"label": "Valid", "count": 12}), True)
        self.assertEqual(before, self.target.read_bytes())

    def test_source_moving_after_validation_prevents_write_and_cleans_temporary(self):
        batch = self.batch({"label": "Baru"})
        before = self.target.read_bytes()
        prepare = apply.prepare_batch

        def move_source(root, proposal):
            prepared = prepare(root, proposal)
            self.source.write_bytes(self.source.read_bytes() + b"\n")
            return prepared

        with patch.object(apply, "prepare_batch", side_effect=move_source):
            with self.assertRaisesRegex(ValueError, "Canonical source changed after validation"):
                apply.apply_batch(self.root, batch, True)
        self.assertEqual(before, self.target.read_bytes())
        self.assertEqual([], list(self.target.parent.glob(".localization-*")))

    def test_locale_alias_empty_declarations_and_xml_literal_text(self):
        self.target = self.xml("values-iw", '<string name="label"/>')
        batch = self.batch({"label": "<b> literal"}, revisions={"label": "Restore missing text"})
        batch["locale"] = "he"
        apply.apply_batch(self.root, batch, True)
        self.assertIn(b'<string name="label">&lt;b&gt; literal</string>', self.target.read_bytes())


if __name__ == "__main__":
    unittest.main()
