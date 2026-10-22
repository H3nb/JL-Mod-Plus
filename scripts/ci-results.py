# SPDX-License-Identifier: Apache-2.0
"""Publish compact, machine-readable CI evidence; missing required reports fail closed."""
import argparse
from collections import Counter
import glob
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def test_results(pattern, no_skips=False):
    reports = sorted(glob.glob(pattern))
    counts = Counter(passed=0, failed=0, errors=0, skipped=0)
    problems = []
    seen = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for case in root.iter('testcase'):
            identity = (case.get('classname'), case.get('name'))
            if identity in seen:
                problems.append(f'Duplicate testcase: {identity}')
            seen.add(identity)
            status = ('errors' if case.find('error') is not None else
                      'failed' if case.find('failure') is not None else
                      'skipped' if case.find('skipped') is not None else 'passed')
            counts[status] += 1
    if not reports:
        problems.append('Required XML reports missing')
    elif not sum(counts.values()):
        problems.append('Required suite executed zero tests')
    if counts['failed'] or counts['errors'] or (no_skips and counts['skipped']):
        problems.append('Required test results did not all pass')
    return dict(kind='tests', reports=reports, counts=dict(counts), problems=problems)


def lint_results(pattern):
    reports = sorted(glob.glob(pattern))
    counts, issues = Counter(), Counter()
    for report in reports:
        root = ET.parse(report).getroot()
        if root.tag != 'issues':
            raise ValueError(f'Invalid lint report: {report}')
        for issue in root.findall('issue'):
            counts[issue.get('severity', 'Unknown')] += 1
            issues[issue.get('id', 'Unknown')] += 1
    problems = [] if reports else ['Required lint XML report missing']
    if counts['Error'] or counts['Fatal']:
        problems.append('Lint reports errors')
    return dict(kind='lint', reports=reports, counts=dict(counts), issue_counts=dict(sorted(issues.items())), problems=problems)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tests', action='append', default=[], metavar='LABEL=GLOB')
    parser.add_argument('--lint', action='append', default=[], metavar='LABEL=GLOB')
    parser.add_argument('--no-skips', action='store_true')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    suites = {}
    for kind, specifications in [('tests', args.tests), ('lint', args.lint)]:
        for specification in specifications:
            label, pattern = specification.split('=', 1)
            if not label or label in suites:
                parser.error('Each report label must be nonempty and unique')
            try:
                suites[label] = test_results(pattern, args.no_skips) if kind == 'tests' else lint_results(pattern)
            except (ET.ParseError, ValueError, OSError) as error:
                suites[label] = dict(kind=kind, counts={}, problems=[f'Unreadable report: {error}'])
    if not suites:
        parser.error('At least one required report must be specified')
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    result = dict(schema_version=1, build_commit=source,
                  pr_head_commit=os.environ.get('PR_HEAD_SHA', ''),
                  run_id=os.environ.get('GITHUB_RUN_ID', ''), run_attempt=os.environ.get('GITHUB_RUN_ATTEMPT', ''),
                  execution='Results from this workspace; task cache/fresh execution must be checked in Gradle logs.',
                  suites=suites)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    lines = [f'CI result evidence — build `{source}`', '', '| Suite | Counts | Result |', '| --- | --- | --- |']
    for label, suite in suites.items():
        counts = ', '.join(f'{key}: {value}' for key, value in suite['counts'].items()) or 'unavailable'
        status = '; '.join(suite['problems']) or 'PASS'
        lines.append(f'| {label} | {counts} | {status} |')
    lines.extend(['', result['execution']])
    summary = '\n'.join(lines)+'\n'
    print(summary)
    args.output.with_suffix('.md').write_text(summary, encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
            output.write(summary)
    raise SystemExit(1 if any(suite['problems'] for suite in suites.values()) else 0)


if __name__ == '__main__':
    main()
