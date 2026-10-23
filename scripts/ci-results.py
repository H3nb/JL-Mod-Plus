# SPDX-License-Identifier: Apache-2.0
"""Publish compact CI evidence; missing, empty and stale required reports fail closed."""
import argparse
from collections import Counter
import glob
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


DETAIL_LIMIT = 200
SUMMARY_LIMIT = 30
OUTCOMES = ('passed', 'failed', 'cancelled', 'not-run', 'skipped-by-policy', 'unavailable')


def compact(value, limit=500):
    """Keep report text on one line, including in GitHub logs and Markdown tables."""
    return ' '.join(str(value or '').split())[:limit]


def markdown(value):
    return compact(value).replace('|', '\\|').replace('`', "'").replace('<', '&lt;')


def report_paths(pattern, since=None):
    reports = sorted(glob.glob(pattern, recursive=True))
    stale = [path for path in reports if since is not None and Path(path).stat().st_mtime < since]
    return [path for path in reports if path not in stale], stale


def test_results(pattern, no_skips=False, since=None):
    reports, stale = report_paths(pattern, since)
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
    if stale:
        problems.append(f'Stale XML reports excluded: {len(stale)}')
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


def lint_results(pattern, since=None):
    reports, stale = report_paths(pattern, since)
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
    if stale:
        problems.append(f'Stale XML reports excluded: {len(stale)}')
    if counts['Error'] or counts['Fatal']:
        problems.append('Lint reports errors')
    return dict(kind='lint', status='unavailable' if not reports else 'failed' if problems else 'passed',
                reports=reports, counts=dict(counts), issue_counts=dict(sorted(issues.items())),
                findings=findings, omitted_finding_count=max(0, finding_count - len(findings)), problems=problems)


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
                cache_outcome='unknown', timings=None, artifacts=artifacts)


def summary(result):
    lines = [f"CI result evidence — build `{result['build_commit']}`", '',
             f"Profile: {markdown(result['profile'])}; request: {markdown(result['request_id']) or 'local'}; "
             f"outcome: {result['outcome']}; scope: {markdown(result['scope'])}",
             f"Expected: {markdown(result['expected_commit']) or 'unspecified'}; PR head: {markdown(result['pr_head_commit']) or 'unavailable'}", '']
    if result['selectors']:
        lines.extend(['Selectors: ' + ', '.join(markdown(item) for item in result['selectors']), ''])
    lines.extend(['| Suite or operation | Counts | Result |', '| --- | --- | --- |'])
    if result.get('operation_result'):
        lines.append(f"| {markdown(result['profile'])} operation | test counts unavailable | {result['operation_result']} |")
    details = []
    omitted_details = 0
    for label, suite in result['suites'].items():
        counts = ', '.join(f'{key}: {value}' for key, value in suite['counts'].items()) or 'unavailable'
        status = '; '.join(suite['problems']) or suite['status']
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
    parser.add_argument('--reports-since', type=float, help='Reject XML older than this Unix timestamp')
    parser.add_argument('--profile', default=os.environ.get('CI_PROFILE', os.environ.get('MODE', 'validate')))
    parser.add_argument('--selector', action='append', default=[])
    parser.add_argument('--task', action='append', default=[])
    parser.add_argument('--expected-sha', default=os.environ.get('EXPECTED_SHA', ''))
    parser.add_argument('--request-id', default=os.environ.get('CI_REQUEST_ID', ''))
    parser.add_argument('--requested-count', type=int, help='Known requested test count, otherwise unknown (null)')
    parser.add_argument('--scope', default=os.environ.get('CI_SCOPE', ''))
    parser.add_argument('--outcome', choices=OUTCOMES, help='Task outcome; report failures still fail closed')
    parser.add_argument('--operation-result', choices=OUTCOMES,
                        help='Explicit whole-operation result for non-XML work; test counts remain unknown')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.requested_count is not None and args.requested_count < 0:
        parser.error('--requested-count must be nonnegative')
    suites = {}
    for kind, specifications in [('tests', args.tests), ('lint', args.lint)]:
        for specification in specifications:
            if '=' not in specification:
                parser.error('Reports must use LABEL=GLOB')
            label, pattern = specification.split('=', 1)
            if not label or label in suites:
                parser.error('Each report label must be nonempty and unique')
            try:
                suites[label] = test_results(pattern, args.no_skips, args.reports_since) if kind == 'tests' else lint_results(pattern, args.reports_since)
            except (ET.ParseError, ValueError, OSError) as error:
                suites[label] = dict(kind=kind, status='unavailable', counts={}, problems=[f'Unreadable report: {compact(error)}'])
    if not suites and args.operation_result is None:
        parser.error('At least one required report or explicit --operation-result must be specified')
    if args.operation_result is not None and (suites or args.outcome):
        parser.error('--operation-result is separate from XML reports and --outcome')
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
    outcome = args.operation_result or args.outcome or inferred
    if outcome == 'passed' and (suite_failed or problems):
        outcome = 'failed'
    result.update(outcome=outcome, suites=suites, problems=problems, operation_result=args.operation_result,
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
