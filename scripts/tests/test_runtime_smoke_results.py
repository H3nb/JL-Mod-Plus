# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('runtime_results', Path(__file__).resolve().parents[1] / 'runtime-smoke-results.py')
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


def record(cls, method, code):
    return f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {code}\n'


def passed(cls='A', method='test'):
    return record(cls, method, 1) + record(cls, method, 0)


class RuntimeResultsTest(unittest.TestCase):
    def test_success_requires_matching_class_and_method(self):
        self.assertEqual(['A#test', 'B#test'], results.verify(passed()+passed('B')+'INSTRUMENTATION_CODE: -1\n', ['A#test', 'B']))

    def test_ignored_assumption_failed_and_error_are_not_passes(self):
        for code in [-1, -2, -3, -4]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                results.verify(record('A', 'test', 1)+record('A', 'test', code)+'INSTRUMENTATION_CODE: -1\n', ['A'])

    def test_missing_method_cannot_match_different_class(self):
        with self.assertRaises(ValueError):
            results.verify(passed('A', 'other')+passed('B')+'INSTRUMENTATION_CODE: -1\n', ['A#test'])

    def test_truncated_duplicate_unpaired_and_empty_runs_fail(self):
        for text in [passed(), record('A','test',1), passed()*2, record('A','test',0), '']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                results.verify(text+'INSTRUMENTATION_CODE: -1\n' if text != passed() else text, ['A'])

    def test_class_selector_rejects_partial_completion(self):
        with self.assertRaises(ValueError):
            results.verify(passed()+record('A','unfinished',1)+'INSTRUMENTATION_CODE: -1\n', ['A'])

    def test_completion_must_be_unique_and_last(self):
        for text in [passed()+'INSTRUMENTATION_CODE: -1\n'*2, 'INSTRUMENTATION_CODE: -1\n'+passed()]:
            with self.assertRaises(ValueError):
                results.verify(text, ['A'])

    def test_declared_count_cannot_hide_missing_class_tests(self):
        text = 'INSTRUMENTATION_STATUS: numtests=2\n'+passed()+'INSTRUMENTATION_CODE: -1\n'
        with self.assertRaises(ValueError):
            results.verify(text, ['A'])
        self.assertEqual(['A#test'], results.verify(text.replace('numtests=2','numtests=1'), ['A']))
