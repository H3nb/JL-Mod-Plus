# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
import os
from pathlib import Path
import sys
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

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


@unittest.skipIf(os.name == 'nt', 'Process signal fixtures use a POSIX executable wrapper')
class ProfileExecutionTest(unittest.TestCase):
    def fixture(self, root, task):
        scripts = root / 'scripts'
        scripts.mkdir()
        for filename in ['ci-profile.py', 'ci-results.py', 'ci_profiles.py']:
            shutil.copyfile(SCRIPTS / filename, scripts / filename)
        wrapper = root / 'gradlew'
        wrapper.write_text('#!' + sys.executable + '\n' + task)
        wrapper.chmod(0o755)
        subprocess.run(['git', 'init', '-q', str(root)], check=True, capture_output=True)
        subprocess.run(['git', '-c', 'user.name=CI fixture', '-c', 'user.email=fixture@example.test',
                        'commit', '-q', '--allow-empty', '-m', 'fixture'], cwd=root, check=True, capture_output=True)
        return [sys.executable, '-B', 'scripts/ci-profile.py', 'unit', '--test', 'p.FutureTest']

    def test_interrupted_execution_publishes_cancelled_result(self):
        for signum in [signal.SIGINT, signal.SIGTERM]:
            with self.subTest(signal=signum), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                command = self.fixture(root, 'from pathlib import Path\nimport time\nPath("task-started").touch()\ntime.sleep(60)\n')
                process = subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    deadline = time.monotonic() + 5
                    while not (root / 'task-started').exists() and process.poll() is None and time.monotonic() < deadline:
                        time.sleep(.01)
                    self.assertTrue((root / 'task-started').exists(), 'Fixture task did not start')
                    process.send_signal(signum)
                    stdout, stderr = process.communicate(timeout=10)
                    self.assertEqual(128 + signum, process.returncode, stderr)
                    value = json.loads((root / 'ci-artifacts/unit-results.json').read_text())
                    self.assertEqual('cancelled', value['outcome'])
                    self.assertEqual('unavailable', value['suites']['unit']['status'])
                    self.assertGreaterEqual(value['timings']['execution_seconds'], 0)
                    self.assertEqual(128 + signum, value['task_returncode'])
                    self.assertIn('outcome: cancelled', stdout)
                    self.assertFalse((root / 'ci-artifacts/unit-timing.json').exists())
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.communicate()

    def test_old_reports_cannot_make_a_zero_execution_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.fixture(root, 'pass\n')
            report = root / 'app/build/test-results/testEmulatorDebugUnitTest/TEST-old.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite><testcase name="old-pass"/></testsuite>')
            process = subprocess.run(command, cwd=root, text=True, capture_output=True)
            self.assertEqual(1, process.returncode, process.stderr)
            self.assertFalse(report.exists())
            value = json.loads((root / 'ci-artifacts/unit-results.json').read_text())
            self.assertEqual('unavailable', value['outcome'])
            self.assertEqual(0, value['executed_count'])

    def test_failed_task_cannot_become_pass_from_successful_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.fixture(root, """from pathlib import Path
report = Path('app/build/test-results/testEmulatorDebugUnitTest/TEST-new.xml')
report.parent.mkdir(parents=True)
report.write_text('<testsuite><testcase name="pass"/></testsuite>')
raise SystemExit(7)
""")
            process = subprocess.run(command, cwd=root, text=True, capture_output=True)
            self.assertEqual(7, process.returncode, process.stderr)
            value = json.loads((root / 'ci-artifacts/unit-results.json').read_text())
            self.assertEqual('failed', value['outcome'])
            self.assertEqual('passed', value['suites']['unit']['status'])
            self.assertEqual(1, value['executed_count'])

    def test_cleanup_error_prevents_task_execution(self):
        with patch.object(sys, 'argv', ['ci-profile.py', 'unit', '--test', 'p.FutureTest']), \
             patch.object(runner.shutil, 'rmtree', side_effect=PermissionError('cannot clear reports')), \
             patch.object(runner.subprocess, 'Popen') as task:
            with self.assertRaises(PermissionError):
                runner.main()
            task.assert_not_called()
