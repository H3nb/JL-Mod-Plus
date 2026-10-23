# SPDX-License-Identifier: Apache-2.0
"""Publish one compact result to JSON, stdout and Markdown; missing reports fail closed."""
import argparse
from collections import Counter
import glob
import json
import math
import os
import runpy
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


DETAIL_LIMIT = 200
SUMMARY_LIMIT = 30
OUTCOMES = ('passed', 'failed', 'cancelled', 'not-run', 'skipped-by-policy', 'unavailable')
STEP_OUTCOMES = {'success': 'passed', 'failure': 'failed', 'cancelled': 'cancelled',
                 'skipped': 'not-run', '': 'not-run'}


def compact(value, limit=500):
    """Keep report text on one line, including in GitHub logs and Markdown tables."""
    return ' '.join(str(value or '').split())[:limit]


def markdown(value):
    return compact(value).replace('|', '\\|').replace('`', "'").replace('<', '&lt;')


def test_results(pattern, no_skips=False):
    reports = sorted(glob.glob(pattern, recursive=True))
    counts = Counter(passed=0, failed=0, errors=0, skipped=0)
    problems, failures = [], []
    seen = set()
    for report in reports:
        root = ET.parse(report).getroot()
        if root.tag not in ('testsuite', 'testsuites'):
            raise ValueError(f'Invalid test report: {report}')
        for case in root.iter('testcase'):
            identity = (case.get('classname'), case.get('name'))
            if identity in seen:
                problems.append(f'Duplicate testcase: {identity}')
            seen.add(identity)
            error, failure = case.find('error'), case.find('failure')
            status = ('errors' if error is not None else
                      'failed' if failure is not None else
                      'skipped' if case.find('skipped') is not None else 'passed')
            counts[status] += 1
            if status in ('errors', 'failed') and len(failures) < DETAIL_LIMIT:
                detail = error if error is not None else failure
                failures.append(dict(classname=compact(identity[0]), name=compact(identity[1]),
                                     status=status, message=compact(detail.get('message') or detail.text),
                                     type=compact(detail.get('type'))))
    executed = counts['passed'] + counts['failed'] + counts['errors']
    if not reports:
        problems.append('Required XML reports missing')
    elif not executed:
        problems.append('Required suite executed zero tests (skipped cases are not execution)')
    if counts['failed'] or counts['errors'] or (no_skips and counts['skipped']):
        problems.append('Required test results did not all pass')
    status = ('unavailable' if not reports else 'not-run' if not executed else
              'failed' if problems else 'passed')
    return dict(kind='tests', status=status, reports=reports, counts=dict(counts),
                executed_count=executed, failed_tests=failures,
                omitted_failure_count=max(0, counts['failed'] + counts['errors'] - len(failures)),
                problems=problems)


def lint_results(pattern):
    reports = sorted(glob.glob(pattern, recursive=True))
    counts, issues = Counter(), Counter()
    findings = []
    finding_count = 0
    for report in reports:
        root = ET.parse(report).getroot()
        if root.tag != 'issues':
            raise ValueError(f'Invalid lint report: {report}')
        for issue in root.findall('issue'):
            severity, rule = issue.get('severity', 'Unknown'), issue.get('id', 'Unknown')
            counts[severity] += 1
            issues[rule] += 1
            finding_count += 1
            if len(findings) < DETAIL_LIMIT:
                locations = [dict(path=compact(location.get('file')), line=location.get('line'),
                                  column=location.get('column')) for location in issue.findall('location')]
                findings.append(dict(rule=rule, severity=severity, message=compact(issue.get('message')),
                                     locations=locations[:10]))
    problems = [] if reports else ['Required lint XML report missing']
    if counts['Error'] or counts['Fatal']:
        problems.append('Lint reports errors')
    return dict(kind='lint', status='unavailable' if not reports else 'failed' if problems else 'passed',
                reports=reports, counts=dict(counts), issue_counts=dict(sorted(issues.items())),
                findings=findings, omitted_finding_count=max(0, finding_count - len(findings)), problems=problems)


def runtime_results(directory):
    """Count only complete batches already checked by the instrumentation verifier."""
    directory = Path(directory)
    verify = runpy.run_path(str(Path(__file__).with_name('runtime-smoke-results.py')))['verify']
    verified_reports = sorted(directory.glob('*.verified.txt'))
    tests = set()
    for report in verified_reports:
        recorded = report.read_text(encoding='utf-8').splitlines()
        actual = verify(report.with_name(report.name.replace('.verified.txt', '.txt')).read_text(encoding='utf-8'), recorded)
        if actual != recorded or tests.intersection(actual):
            raise ValueError('Runtime verified evidence is inconsistent or duplicated')
        tests.update(actual)
    selectors = []
    for line in (directory / 'selectors.txt').read_text(encoding='utf-8').splitlines():
        status, separator, selector = line.partition('\t')
        if status != 'PASS' or not separator or not selector:
            raise ValueError('Invalid runtime selector evidence')
        selectors.append(selector)
    if not tests or not selectors or len(set(selectors)) != len(selectors):
        raise ValueError('Runtime verified cases/selectors are missing or duplicated')
    def matches(selector, test):
        return selector == test if '#' in selector else test.startswith(selector + '#')
    if any(not any(matches(selector, test) for test in tests) for selector in selectors):
        raise ValueError('Runtime selector has no verified test')
    if any(not any(matches(selector, test) for selector in selectors) for test in tests):
        raise ValueError('Runtime test has no verified selector')
    return dict(kind='runtime', status='passed', reports=[str(path) for path in verified_reports],
                counts={'passed': len(tests)}, executed_count=len(tests), selectors=selectors,
                count_scope='Verified completed batches only; failed/incomplete batch counts are unavailable',
                problems=[])


def step_outcome(values):
    outcomes = {STEP_OUTCOMES[value] for value in values}
    return next(outcome for outcome in ('failed', 'cancelled', 'not-run', 'passed') if outcome in outcomes)


def json_environment(name, default):
    raw = os.environ.get(name, '')
    return json.loads(raw) if raw else default


def metadata(args):
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
    selectors = args.selector or ([os.environ['CI_SELECTOR']] if os.environ.get('CI_SELECTOR') else [])
    tasks = args.task or json_environment('CI_TASKS', [])
    artifacts = json_environment('CI_ARTIFACTS', None)
    if not isinstance(tasks, list) or any(not isinstance(task, str) for task in tasks):
        raise ValueError('CI_TASKS must be a JSON array of task names')
    if artifacts is not None and not isinstance(artifacts, list):
        raise ValueError('CI_ARTIFACTS must be a JSON array; omit it when artifact metadata is unavailable')
    run_id, repository = os.environ.get('GITHUB_RUN_ID', ''), os.environ.get('GITHUB_REPOSITORY', '')
    return dict(schema_version=2, build_commit=source, expected_commit=args.expected_sha,
                source_tree=tree, pr_number=os.environ.get('PR_NUMBER', ''),
                pr_head_commit=os.environ.get('PR_HEAD_SHA', ''), pr_base_commit=os.environ.get('PR_BASE_SHA', ''),
                synthetic_merge_commit=os.environ.get('GITHUB_SHA', '') if os.environ.get('GITHUB_EVENT_NAME') == 'pull_request' else '',
                profile=args.profile, selectors=selectors, tasks=tasks, request_id=args.request_id or (f'run-{run_id}' if run_id else ''),
                run_id=run_id, run_attempt=os.environ.get('GITHUB_RUN_ATTEMPT', ''), job_id=os.environ.get('GITHUB_JOB', ''),
                run_url=f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{repository}/actions/runs/{run_id}" if repository and run_id else '',
                requested_count=args.requested_count, requested_selector_count=len(selectors),
                scope=args.scope or ('filtered run' if selectors else 'profile results; see tasks'),
                execution=os.environ.get('CI_EXECUTION', 'XML evidence; task cache/fresh execution must be checked in Gradle logs.'),
                cache_outcome='unknown',
                timings={'execution_seconds': args.execution_seconds} if args.execution_seconds is not None else None,
                task_returncode=args.task_returncode, artifacts=artifacts)


def summary(result):
    lines = [f"CI result evidence — build `{result['build_commit']}`", '',
             f"Profile: {markdown(result['profile'])}; request: {markdown(result['request_id']) or 'local'}; "
             f"outcome: {result['outcome']}; scope: {markdown(result['scope'])}",
             f"Expected: {markdown(result['expected_commit']) or 'unspecified'}; PR head: {markdown(result['pr_head_commit']) or 'unavailable'}", '']
    if result.get('timings'):
        lines.extend([f"Measured execution: {result['timings']['execution_seconds']} seconds; task return code: {result['task_returncode']}", ''])
    if result['selectors']:
        lines.extend(['Selectors: ' + ', '.join(markdown(item) for item in result['selectors']), ''])
    lines.extend(['| Suite or operation | Counts | Result |', '| --- | --- | --- |'])
    if not result['suites']:
        lines.append(f"| {markdown(result['profile'])} operation | test counts unavailable | {result['outcome']} |")
    details = []
    omitted_details = 0
    for label, suite in result['suites'].items():
        counts = ', '.join(f'{key}: {value}' for key, value in suite['counts'].items()) or 'unavailable'
        status = '; '.join(suite['problems']) or suite['status']
        if suite.get('count_scope'):
            status += '; ' + suite['count_scope']
        lines.append(f'| {markdown(label)} | {counts} | {markdown(status)} |')
        omitted_details += suite.get('omitted_failure_count', 0) + suite.get('omitted_finding_count', 0)
        for failure in suite.get('failed_tests', []):
            name = failure['classname'] + '#' + failure['name']
            details.append(f"- Test {markdown(label)}: {markdown(name)} — {markdown(failure['message']) or failure['status']}")
        for finding in suite.get('findings', []):
            locations = finding['locations']
            location = (f"{locations[0]['path']}:{locations[0]['line'] or '?'}" if locations else 'location unavailable')
            details.append(f"- Lint {markdown(finding['severity'])} {markdown(finding['rule'])} {markdown(location)} — {markdown(finding['message'])}")
    if details:
        lines.extend(['', *details[:SUMMARY_LIMIT]])
        omitted_details += max(0, len(details) - SUMMARY_LIMIT)
        if omitted_details:
            lines.append(f'- {omitted_details} additional details omitted; inspect JSON/XML artifacts.')
    for problem in result['problems']:
        lines.append('- ' + markdown(problem))
    lines.extend(['', result['execution'], 'Artifact metadata is unavailable until upload.' if result['artifacts'] is None else 'Artifact metadata is included in JSON.'])
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tests', action='append', default=[], metavar='LABEL=GLOB')
    parser.add_argument('--lint', action='append', default=[], metavar='LABEL=GLOB')
    parser.add_argument('--no-skips', action='store_true')
    parser.add_argument('--runtime-results', type=Path, help='Runtime-smoke verified batch evidence directory')
    parser.add_argument('--profile', default=os.environ.get('CI_PROFILE', os.environ.get('MODE', 'validate')))
    parser.add_argument('--selector', action='append', default=[])
    parser.add_argument('--task', action='append', default=[])
    parser.add_argument('--expected-sha', default=os.environ.get('EXPECTED_SHA', ''))
    parser.add_argument('--request-id', default=os.environ.get('CI_REQUEST_ID', ''))
    parser.add_argument('--requested-count', type=int, help='Known requested test count, otherwise unknown (null)')
    parser.add_argument('--scope', default=os.environ.get('CI_SCOPE', ''))
    parser.add_argument('--outcome', choices=OUTCOMES, help='Task outcome; report failures still fail closed')
    parser.add_argument('--step-outcome', action='append', choices=STEP_OUTCOMES,
                        help='GitHub step outcome; repeat for all required operation steps')
    parser.add_argument('--execution-seconds', type=float, help='Measured execution duration, otherwise unknown')
    parser.add_argument('--task-returncode', type=int)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.requested_count is not None and args.requested_count < 0:
        parser.error('--requested-count must be nonnegative')
    if args.execution_seconds is not None and (not math.isfinite(args.execution_seconds) or args.execution_seconds < 0):
        parser.error('--execution-seconds must be finite and nonnegative')
    if args.outcome and args.step_outcome:
        parser.error('Use either --outcome or --step-outcome')
    explicit_outcome = step_outcome(args.step_outcome) if args.step_outcome else args.outcome
    suites = {}
    for kind, specifications in [('tests', args.tests), ('lint', args.lint)]:
        for specification in specifications:
            if '=' not in specification:
                parser.error('Reports must use LABEL=GLOB')
            label, pattern = specification.split('=', 1)
            if not label or label in suites:
                parser.error('Each report label must be nonempty and unique')
            try:
                suites[label] = test_results(pattern, args.no_skips) if kind == 'tests' else lint_results(pattern)
            except (ET.ParseError, ValueError, OSError) as error:
                suites[label] = dict(kind=kind, status='unavailable', counts={}, problems=[f'Unreadable report: {compact(error)}'])
    if args.runtime_results is not None:
        if 'runtime' in suites:
            parser.error('runtime report label is reserved for --runtime-results')
        try:
            suites['runtime'] = runtime_results(args.runtime_results)
        except (ValueError, OSError) as error:
            suites['runtime'] = dict(kind='runtime', status='unavailable', counts={},
                                     problems=[f'Runtime evidence unavailable: {compact(error)}'])
    if not suites and explicit_outcome is None:
        parser.error('Required reports or an explicit --outcome/--step-outcome must be specified')
    try:
        result = metadata(args)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    problems = []
    if args.expected_sha and args.expected_sha != result['build_commit']:
        problems.append('Built revision differs from expected SHA; results are stale for this request')
    suite_failed = any(suite['problems'] for suite in suites.values())
    report_statuses = {suite['status'] for suite in suites.values()}
    inferred = ('failed' if problems or 'failed' in report_statuses else
                'unavailable' if 'unavailable' in report_statuses else
                'not-run' if 'not-run' in report_statuses else 'passed')
    outcome = explicit_outcome or inferred
    if outcome == 'passed' and (suite_failed or problems):
        outcome = 'failed'
    result.update(outcome=outcome, suites=suites, problems=problems,
                  executed_count=sum(suite.get('executed_count', 0) for suite in suites.values()) if suites else None)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    rendered = summary(result)
    print(rendered)
    args.output.with_suffix('.md').write_text(rendered, encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
            output.write(rendered)
    raise SystemExit(1 if outcome != 'passed' or suite_failed or problems else 0)


if __name__ == '__main__':
    main()
