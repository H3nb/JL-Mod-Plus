# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('ci_results', Path(__file__).resolve().parents[1] / 'ci-results.py')
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


class CIResultsTest(unittest.TestCase):
    def evaluate(self, xml, lint=False, no_skips=False):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'report.xml'
            if xml is not None:
                file.write_text(xml)
            return results.lint_results(str(file)) if lint else results.test_results(str(file), no_skips)

    def test_counts_actual_cases_and_disambiguates_states(self):
        value = self.evaluate('<testsuite><testcase name="pass"/><testcase name="fail"><failure/></testcase><testcase name="error"><error/></testcase><testcase name="skip"><skipped/></testcase></testsuite>')
        self.assertEqual(dict(passed=1, failed=1, errors=1, skipped=1), value['counts'])
        self.assertTrue(value['problems'])

    def test_missing_empty_and_duplicate_fail_closed(self):
        for xml in [None, '<testsuite tests="122"/>', '<testsuite><testcase name="x"/><testcase name="x"/></testsuite>']:
            with self.subTest(xml=xml):
                self.assertTrue(self.evaluate(xml)['problems'])

    def test_skip_policy(self):
        xml = '<testsuite><testcase name="x"><skipped/></testcase></testsuite>'
        self.assertFalse(self.evaluate(xml)['problems'])
        self.assertTrue(self.evaluate(xml, no_skips=True)['problems'])

    def test_lint_warning_counts_without_promoting_to_errors(self):
        value = self.evaluate('<issues><issue id="One" severity="Warning"/><issue id="One" severity="Warning"/></issues>', lint=True)
        self.assertEqual({'Warning': 2}, value['counts'])
        self.assertEqual({'One': 2}, value['issue_counts'])
        self.assertFalse(value['problems'])
        self.assertTrue(self.evaluate('<issues><issue id="Bad" severity="Error"/></issues>', lint=True)['problems'])
        self.assertTrue(self.evaluate(None, lint=True)['problems'])
