# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
import os
import subprocess
import sys
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
        self.assertTrue(self.evaluate(xml)['problems'])
        mixed = '<testsuite><testcase name="pass"/>' + xml.removeprefix('<testsuite>')
        self.assertFalse(self.evaluate(mixed)['problems'])
        self.assertTrue(self.evaluate(mixed, no_skips=True)['problems'])

    def test_lint_warning_counts_without_promoting_to_errors(self):
        value = self.evaluate('<issues><issue id="One" severity="Warning"/><issue id="One" severity="Warning"/></issues>', lint=True)
        self.assertEqual({'Warning': 2}, value['counts'])
        self.assertEqual({'One': 2}, value['issue_counts'])
        self.assertFalse(value['problems'])
        self.assertTrue(self.evaluate('<issues><issue id="Bad" severity="Error"/></issues>', lint=True)['problems'])
        self.assertTrue(self.evaluate(None, lint=True)['problems'])

    def test_failed_case_names_messages_and_truncation(self):
        value = self.evaluate('<testsuite><testcase classname="pkg.FailureTest" name="regression"><failure type="AssertionError" message="expected a&#10;actual b">stack trace</failure></testcase></testsuite>')
        self.assertEqual('failed', value['status'])
        self.assertEqual(1, value['executed_count'])
        self.assertEqual(dict(classname='pkg.FailureTest', name='regression', status='failed',
                              message='expected a actual b', type='AssertionError'), value['failed_tests'][0])
        self.assertEqual(0, value['omitted_failure_count'])

    def test_actionable_lint_locations_and_message(self):
        value = self.evaluate('<issues><issue id="RiskyApi" severity="Warning" message="Use supported API"><location file="app/src/Main.kt" line="12" column="3"/></issue></issues>', lint=True)
        self.assertEqual(dict(rule='RiskyApi', severity='Warning', message='Use supported API',
                              locations=[dict(path='app/src/Main.kt', line='12', column='3')]), value['findings'][0])
        self.assertFalse(value['problems'])

    def test_step_outcomes_use_required_step_precedence(self):
        for steps, expected in [(['success'], 'passed'), (['success', 'failure'], 'failed'),
                                (['cancelled', 'failure'], 'failed'), (['success', 'cancelled'], 'cancelled'),
                                (['success', 'skipped'], 'not-run'), (['success', ''], 'not-run')]:
            with self.subTest(steps=steps):
                self.assertEqual(expected, results.step_outcome(steps))

    def test_cli_json_context_stdout_and_expected_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'TEST.xml'
            output = Path(directory) / 'result.json'
            report.write_text('<testsuite><testcase classname="pkg.Regression" name="case"><failure message="the cause"/></testcase></testsuite>')
            command = [sys.executable, str(Path(results.__file__)), '--tests', f'focused={report}',
                       '--output', str(output), '--profile', 'unit', '--selector', 'pkg.Regression',
                       '--task', ':app:testEmulatorDebugUnitTest', '--request-id', 'comment-123',
                       '--expected-sha', '0' * 40]
            process = subprocess.run(command, cwd=Path(results.__file__).resolve().parents[1],
                                     text=True, capture_output=True)
            self.assertEqual(1, process.returncode, process.stderr)
            value = json.loads(output.read_text())
            self.assertEqual(2, value['schema_version'])
            self.assertEqual('unit', value['profile'])
            self.assertEqual('comment-123', value['request_id'])
            self.assertEqual(['pkg.Regression'], value['selectors'])
            self.assertEqual([':app:testEmulatorDebugUnitTest'], value['tasks'])
            self.assertEqual('filtered run', value['scope'])
            self.assertEqual(1, value['executed_count'])
            self.assertIsNone(value['requested_count'])
            self.assertEqual('unknown', value['cache_outcome'])
            self.assertTrue(value['problems'])
            self.assertIn('pkg.Regression#case', process.stdout)
            self.assertIn('the cause', process.stdout)
            self.assertEqual(process.stdout.rstrip(), output.with_suffix('.md').read_text().rstrip())

    def test_cli_missing_report_and_cancelled_status_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'result.json'
            command = [sys.executable, str(Path(results.__file__)), '--tests', 'focused=no-such-report',
                       '--output', str(output)]
            subprocess.run(command, cwd=Path(results.__file__).resolve().parents[1], capture_output=True)
            value = json.loads(output.read_text())
            self.assertEqual('unavailable', value['outcome'])
            subprocess.run(command + ['--outcome', 'cancelled'],
                           cwd=Path(results.__file__).resolve().parents[1], capture_output=True)
            value = json.loads(output.read_text())
            self.assertEqual('cancelled', value['outcome'])
            self.assertEqual('unavailable', value['suites']['focused']['status'])

    def test_summary_is_bounded_and_escapes_report_text(self):
        xml = '<testsuite>' + ''.join(f'<testcase name="case{i}"><failure message="a|b&#10;::warning::text"/></testcase>' for i in range(250)) + '</testsuite>'
        suite = self.evaluate(xml)
        self.assertEqual(results.DETAIL_LIMIT, len(suite['failed_tests']))
        self.assertEqual(50, suite['omitted_failure_count'])
        value = dict(build_commit='a' * 40, profile='unit', request_id='123', outcome='failed',
                     scope='filtered run', expected_commit='', pr_head_commit='', selectors=[],
                     suites={'unit': suite}, problems=[], execution='unknown', artifacts=None)
        rendered = results.summary(value)
        self.assertEqual(results.SUMMARY_LIMIT, rendered.count('- Test '))
        self.assertIn('220 additional details omitted', rendered)
        self.assertIn('a\\|b ::warning::text', rendered)
        self.assertNotIn('\n::warning::', rendered)


    def test_connected_reports_glob_covers_zero_one_and_deeper_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, suffix in enumerate(['TEST-root.xml', 'device/TEST-device.xml', 'device/variant/TEST-deep.xml']):
                path = root / suffix
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f'<testsuite><testcase name="case{index}"/></testsuite>')
            value = results.test_results(str(root / '**/TEST-*.xml'))
            self.assertEqual(3, value['executed_count'])
            self.assertFalse(value['problems'])


    def test_non_xml_operations_require_explicit_status_and_never_invent_test_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'operation.json'
            command = [sys.executable, str(Path(results.__file__)), '--profile', 'release-check',
                       '--scope', 'release packaging and startup only; inspect detailed logs',
                       '--output', str(output)]
            process = subprocess.run(command, cwd=Path(results.__file__).resolve().parents[1], capture_output=True)
            self.assertEqual(2, process.returncode)
            self.assertFalse(output.exists())
            for status in results.OUTCOMES:
                process = subprocess.run(command + ['--outcome', status],
                                         cwd=Path(results.__file__).resolve().parents[1],
                                         text=True, capture_output=True)
                self.assertEqual(0 if status == 'passed' else 1, process.returncode, process.stderr)
                value = json.loads(output.read_text())
                self.assertEqual(status, value['outcome'])
                self.assertIsNone(value['executed_count'])
                self.assertIsNone(value['requested_count'])
                self.assertEqual({}, value['suites'])
                self.assertIn('test counts unavailable', process.stdout)
            process = subprocess.run(command + ['--step-outcome', 'success', '--tests', 'unit=missing'],
                                     cwd=Path(results.__file__).resolve().parents[1], capture_output=True)
            self.assertEqual(1, process.returncode)
            self.assertEqual('failed', json.loads(output.read_text())['outcome'])

    def test_successful_xml_does_not_hide_failed_compile_or_assembly_step(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'TEST.xml'
            output = Path(directory) / 'result.json'
            report.write_text('<testsuite><testcase name="passed"/></testsuite>')
            process = subprocess.run([sys.executable, str(Path(results.__file__)), '--tests', f'unit={report}',
                                      '--step-outcome', 'success', '--step-outcome', 'failure',
                                      '--output', str(output)], cwd=Path(results.__file__).resolve().parents[1],
                                     capture_output=True)
            self.assertEqual(1, process.returncode)
            value = json.loads(output.read_text())
            self.assertEqual('failed', value['outcome'])
            self.assertEqual('passed', value['suites']['unit']['status'])

    def test_runtime_counts_are_crosschecked_against_actual_complete_batches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = ''.join(f'INSTRUMENTATION_STATUS: class=p.RuntimeTest\nINSTRUMENTATION_STATUS: test=case\nINSTRUMENTATION_STATUS_CODE: {code}\n' for code in [1, 0])
            (root / 'main.txt').write_text(records + 'INSTRUMENTATION_CODE: -1\n')
            (root / 'main.verified.txt').write_text('p.RuntimeTest#case\n')
            (root / 'selectors.txt').write_text('PASS\tp.RuntimeTest\n')
            suite = results.runtime_results(root)
            self.assertEqual(1, suite['executed_count'])
            self.assertEqual({'passed': 1}, suite['counts'])
            self.assertIn('completed batches only', suite['count_scope'])
            # A forged/truncated verified file must not manufacture another case.
            (root / 'main.verified.txt').write_text('p.RuntimeTest#case\np.RuntimeTest#missing\n')
            with self.assertRaises(ValueError):
                results.runtime_results(root)

    def test_runtime_failed_later_batch_keeps_partial_counts_and_failed_outcome(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = ''.join(f'INSTRUMENTATION_STATUS: class=p.RuntimeTest\nINSTRUMENTATION_STATUS: test=case\nINSTRUMENTATION_STATUS_CODE: {code}\n' for code in [1, 0])
            (root / 'main.txt').write_text(records + 'INSTRUMENTATION_CODE: -1\n')
            (root / 'main.verified.txt').write_text('p.RuntimeTest#case\n')
            (root / 'selectors.txt').write_text('PASS\tp.RuntimeTest\n')
            (root / 'isolated.txt').write_text('INSTRUMENTATION_FAILED: Process crashed\n')
            output = root / 'result.json'
            process = subprocess.run([sys.executable, str(Path(results.__file__)), '--runtime-results', directory,
                                      '--step-outcome', 'failure', '--output', str(output)],
                                     cwd=Path(results.__file__).resolve().parents[1], text=True, capture_output=True)
            self.assertEqual(1, process.returncode, process.stderr)
            value = json.loads(output.read_text())
            self.assertEqual('failed', value['outcome'])
            self.assertEqual(1, value['executed_count'])
            self.assertIn('failed/incomplete batch counts are unavailable', process.stdout)
