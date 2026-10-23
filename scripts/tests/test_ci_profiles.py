# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from ci_profiles import validate_selector
spec = importlib.util.spec_from_file_location('profile_runner', SCRIPTS / 'ci-profile.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ProfileTest(unittest.TestCase):
    def test_new_classes_and_methods_need_no_allowlist(self):
        for profile, selector in [('unit', 'new.package.FutureTest'), ('unit', 'new.package.FutureTest.a Kotlin method[0]'),
                                  ('instrumentation', 'new.package.FutureTest#regressionCase')]:
            with self.subTest(profile=profile):
                arguments, _, _ = runner.plan(profile, selector)
                self.assertIn('--rerun', arguments)
                self.assertTrue(any(selector in item for item in arguments))
                self.assertNotIn('--rerun-tasks', arguments)

    def test_rejects_flags_tasks_paths_and_unbounded_input(self):
        for selector in ['--tests foo', ':app:assemble', '../test', 'Foo\nBar', 'p.Test; echo bad', 'p.Test$(id)', 'x'*301, ' p.Test']:
            for profile in ['unit', 'instrumentation']:
                with self.subTest(selector=selector, profile=profile):
                    with self.assertRaises(ValueError):
                        validate_selector(profile, selector)
        with self.assertRaises(ValueError):
            validate_selector('validate', 'p.Test')
        with self.assertRaises(ValueError):
            validate_selector('instrumentation', 'p.Test#valid,other.package.OtherTest')
