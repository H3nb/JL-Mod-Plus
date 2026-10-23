# SPDX-License-Identifier: Apache-2.0
"""Run focused profiles identically in CI and locally; report only this invocation's XML."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from ci_profiles import validate_selector

COMMON = ['--daemon', '--stacktrace', '--profile', '--configuration-cache', '--configuration-cache-problems=fail']
UNIT_TASK = ':app:testEmulatorDebugUnitTest'
DEVICE_TASK = ':app:connectedEmulatorDebugAndroidTest'


def plan(profile, selector):
    validate_selector(profile, selector)
    if profile == 'unit':
        return (['-PjlmodNativeBuild=false', UNIT_TASK, '--tests', selector, '--rerun'],
                ['app/build/test-results/testEmulatorDebugUnitTest'], [UNIT_TASK])
    if profile == 'instrumentation':
        return (['-PjlmodRuntimeTestAbi=x86_64',
                 '-Pandroid.testInstrumentationRunnerArguments.class=' + selector, DEVICE_TASK, '--rerun'],
                ['app/build/outputs/androidTest-results/connected'], [DEVICE_TASK])
    raise ValueError('This runner accepts unit or instrumentation; other profiles use documented workflow tasks')


class Cancelled(Exception):
    pass


def run_task(command):
    """Forward cancellation to the task, then allow the caller to publish evidence."""
    def interrupted(signum, _frame):
        raise Cancelled(signum)

    previous = {signum: signal.signal(signum, interrupted) for signum in (signal.SIGINT, signal.SIGTERM)}
    task = None
    try:
        task = subprocess.Popen(command, start_new_session=os.name != 'nt')
        return task.wait()
    except Cancelled as error:
        # A second cancellation must not interrupt task cleanup. A hard runner kill
        # can still prevent result publication; GitHub's run status remains authoritative.
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        signum = error.args[0]
        if task is not None:
            try:
                if os.name == 'nt':
                    task.terminate()
                else:
                    os.killpg(task.pid, signum)
            except ProcessLookupError:
                pass  # The task may have exited between cancellation and signal delivery.
            try:
                task.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    if os.name == 'nt':
                        task.kill()
                    else:
                        os.killpg(task.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                task.wait()
        return 128 + signum
    except OSError as error:
        print(f'Cannot execute test task: {error}', file=sys.stderr)
        return 127
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profile', choices=['unit', 'instrumentation'])
    parser.add_argument('--test', required=True)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    try:
        arguments, directories, tasks = plan(args.profile, args.test)
    except ValueError as error:
        parser.error(str(error))
    wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
    command = [wrapper, *COMMON, *arguments]
    print('Profile command (argument array): ' + json.dumps(command), flush=True)
    if args.dry_run:
        return
    for directory in directories:
        try:
            shutil.rmtree(directory)
        except FileNotFoundError:
            pass
        # Permission and other cleanup errors fail before Gradle; stale reports
        # must never be accepted as output of this forced invocation.
    started = time.monotonic()
    artifacts = Path('ci-artifacts')
    artifacts.mkdir(exist_ok=True)
    # No shell; selector is one Gradle/runner argument. Only the selected test task is forced.
    outcome = run_task(command)
    elapsed = round(time.monotonic() - started, 3)
    environment = dict(os.environ, CI_PROFILE=args.profile, CI_SELECTOR=args.test,
                       CI_TASKS=json.dumps(tasks), CI_EXECUTION='Selected test task forced with --rerun; dependencies may reuse cache.')
    reporter = [sys.executable, '-B', 'scripts/ci-results.py',
                '--execution-seconds', str(elapsed), '--task-returncode', str(outcome),
                '--scope', 'filtered run; instrumentation executes on API 35 x86_64 in CI' if args.profile == 'instrumentation' else 'filtered app JVM run',
                '--output', str(artifacts / (args.profile + '-results.json'))]
    for directory in directories:
        reporter += ['--tests', args.profile + '=' + directory + '/**/TEST-*.xml'] if args.profile == 'instrumentation' else ['--tests', args.profile + '=' + directory + '/TEST-*.xml']
    if outcome:
        reporter += ['--outcome', 'cancelled' if outcome in (130, 143, -2, -15) else 'failed']
    reported = subprocess.run(reporter, env=environment, check=False).returncode
    raise SystemExit(outcome or reported)


if __name__ == '__main__':
    main()
