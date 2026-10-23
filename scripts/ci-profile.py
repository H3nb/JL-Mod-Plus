# SPDX-License-Identifier: Apache-2.0
"""Run focused profiles identically in CI and locally; report only this invocation's XML."""
import argparse
import json
import os
from pathlib import Path
import shutil
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
        shutil.rmtree(directory, ignore_errors=True)
    started = time.time()
    artifacts = Path('ci-artifacts')
    artifacts.mkdir(exist_ok=True)
    # No shell; selector is one Gradle/runner argument. Only the selected test task is forced.
    outcome = subprocess.run(command, check=False).returncode
    environment = dict(os.environ, CI_PROFILE=args.profile, CI_SELECTOR=args.test,
                       CI_TASKS=json.dumps(tasks), CI_EXECUTION='Selected test task forced with --rerun; dependencies may reuse cache.')
    reporter = [sys.executable, '-B', 'scripts/ci-results.py', '--reports-since', str(started),
                '--scope', 'filtered run; instrumentation executes on API 35 x86_64 in CI' if args.profile == 'instrumentation' else 'filtered app JVM run',
                '--output', str(artifacts / (args.profile + '-results.json'))]
    for directory in directories:
        reporter += ['--tests', args.profile + '=' + directory + '/**/TEST-*.xml'] if args.profile == 'instrumentation' else ['--tests', args.profile + '=' + directory + '/TEST-*.xml']
    if outcome:
        reporter += ['--outcome', 'cancelled' if outcome in (130, -2, -15) else 'failed']
    reported = subprocess.run(reporter, env=environment, check=False).returncode
    timing = dict(schema_version=1, profile=args.profile, selector=args.test, tasks=tasks,
                  execution_seconds=round(time.time() - started, 3), task_returncode=outcome,
                  cache_outcome='unknown', queue_seconds=None, setup_seconds=None,
                  download_seconds=None, upload_seconds=None, cache_post_seconds=None)
    (artifacts / (args.profile + '-timing.json')).write_text(json.dumps(timing, indent=2) + '\n', encoding='utf-8')
    raise SystemExit(outcome or reported)


if __name__ == '__main__':
    main()
