# SPDX-License-Identifier: Apache-2.0
"""Dispatch trusted Android CI for deliberate, authorized PR comment requests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

from ci_profiles import PROFILES, validate_selector

COMMAND = '/jlmod-ci'
SHA = re.compile(r'[0-9a-f]{40}')
API_VERSION = '2026-03-10'
WORKFLOW = 'android.yml'


class Reject(RuntimeError):
    pass


def parse_request(body):
    if not isinstance(body, str) or len(body) > 700 or '\n' in body or '\r' in body:
        raise Reject('Use one standalone command line, at most 700 characters.')
    if not body.startswith(COMMAND + ' ') or body != body.strip():
        raise Reject('Expected a standalone /jlmod-ci command.')
    try:
        words = shlex.split(body, posix=True)
    except ValueError as error:
        raise Reject(str(error)) from error
    if len(words) < 2 or words[:1] != [COMMAND] or words[1] not in PROFILES:
        raise Reject('Unknown CI profile.')
    profile, selector, expected = words[1], '', ''
    flags = words[2:]
    if len(flags) % 2 or len(flags) > 4:
        raise Reject('Only --test SELECTOR and --sha FULL_SHA are supported.')
    seen = set()
    for flag, value in zip(flags[::2], flags[1::2]):
        if flag in seen:
            raise Reject('Duplicate command option.')
        seen.add(flag)
        if flag == '--test' and profile in {'unit', 'instrumentation'}:
            try:
                validate_selector(profile, value)
            except ValueError as error:
                raise Reject(str(error)) from error
            selector = value
        elif flag == '--sha' and SHA.fullmatch(value):
            expected = value
        else:
            raise Reject('Invalid option or selector. Use an exact package.Class[.method] (unit) or package.Class[#method] (instrumentation).')
    if (profile in {'unit', 'instrumentation'}) != bool(selector):
        raise Reject('The unit and instrumentation profiles require one --test selector.')
    return dict(mode=profile, test_selector=selector, expected_sha=expected)


def allowed(event, actor_ids):
    if event.get('action') != 'created' or not (event.get('issue') or {}).get('pull_request'):
        return False
    sender = (event.get('sender') or {}).get('id')
    comment_user = ((event.get('comment') or {}).get('user') or {}).get('id')
    if not isinstance(sender, int) or isinstance(sender, bool) or sender != comment_user:
        return False
    owner = ((event.get('repository') or {}).get('owner') or {}).get('id')
    if sender == owner:
        return True
    # Only complete numeric tokens grant an identity. There is no wildcard or
    # implicit bot/collaborator authorization, including with malformed values.
    return f'|{sender}|' in (actor_ids or '')


def api(path, method='GET', payload=None):
    args = ['gh', 'api', '-H', f'X-GitHub-Api-Version: {API_VERSION}', path]
    if method != 'GET':
        args += ['--method', method, '--input', '-']
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            text=True, capture_output=True, check=False)
    if result.returncode:
        raise Reject(f'GitHub API {method} failed: {result.stderr.strip()[:800]}')
    try:
        return json.loads(result.stdout) if result.stdout.strip() else None
    except ValueError as error:
        raise Reject('GitHub API returned unreadable JSON.') from error


def title(inputs):
    return (f"PR {inputs['pr_number']} | {inputs['mode']} | {inputs['expected_sha']} | "
            f"test {inputs.get('selector_key') or '-'} | request {inputs['request_id']}")


def find_run(repo, inputs, default_branch, active_identical=True):
    expected = title(inputs)
    prefix = expected.rsplit(' | request ', 1)[0] + ' | request '
    for page in range(1, 11):
        data = api(f'repos/{repo}/actions/workflows/{WORKFLOW}/runs?event=workflow_dispatch&per_page=100&page={page}')
        runs = data.get('workflow_runs', [])
        for run in runs:
            if (run.get('path') != '.github/workflows/android.yml' or run.get('head_branch') != default_branch
                    or (run.get('head_repository') or {}).get('full_name') != repo):
                continue
            actual = run.get('display_title', '')
            if actual == expected or (active_identical and actual.startswith(prefix) and run.get('status') != 'completed'):
                return run
        if len(runs) < 100:
            return None
    raise Reject('Run history search reached 1,000 entries; inspect history before dispatching again.')


def dispatch(repo, default_branch, inputs, rerun=False):
    existing = find_run(repo, inputs, default_branch)
    if existing:
        return existing['html_url'], 'Existing request or identical active run recovered; no new dispatch.'
    # A controller retry may follow a committed dispatch whose response was lost.
    # Absence from a bounded, eventually-consistent listing is not proof it failed.
    if rerun:
        raise Reject('No matching run is visible on this controller retry. Inspect Actions history; do not redispatch the old comment automatically. If no run exists, create a new deliberate request.')
    try:
        response = api(f'repos/{repo}/actions/workflows/{WORKFLOW}/dispatches', 'POST',
                       {'ref': default_branch, 'inputs': inputs})
        if not isinstance(response, dict) or not response.get('workflow_run_id') or not response.get('html_url'):
            raise Reject('Dispatch response has no run details.')
        return response['html_url'], 'Dispatched trusted default-branch workflow for the exact PR source SHA.'
    except Reject as error:
        # Recover by exact request identity only. Never blindly repeat POST.
        for attempt in range(3):
            if attempt:
                time.sleep(2)
            recovered = find_run(repo, inputs, default_branch, active_identical=False)
            if recovered:
                return recovered['html_url'], 'Dispatch response was unavailable; exact request run recovered.'
        raise Reject(f'Dispatch outcome uncertain: {error}. No automatic redispatch; inspect Actions using request {inputs["request_id"]}.') from error


def process(event, actor_ids, rerun=False):
    if not allowed(event, actor_ids):
        raise Reject('Only explicitly authorized actors may request CI on PRs.')
    request = parse_request(event['comment']['body'])
    repo = event['repository']['full_name']
    if repo != os.environ.get('GITHUB_REPOSITORY'):
        raise Reject('Event repository mismatch.')
    pr_number = event['issue']['number']
    if type(pr_number) is not int or pr_number <= 0:
        raise Reject('Invalid PR number.')
    comment_id = event['comment']['id']
    if type(comment_id) is not int or comment_id <= 0:
        raise Reject('Invalid comment request ID.')
    request_id = f'comment-{comment_id}'
    try:
        pr = api(f'repos/{repo}/pulls/{pr_number}')
        if (pr.get('state') != 'open' or (pr.get('head', {}).get('repo') or {}).get('full_name') != repo
                or (pr.get('base', {}).get('repo') or {}).get('full_name') != repo):
            raise Reject('CI commands require an open same-repository PR; fork PRs use the existing PR CI policy.')
        head = pr['head']['sha']
        if not SHA.fullmatch(head):
            raise Reject('Invalid PR head SHA.')
        if request['expected_sha'] and request['expected_sha'] != head:
            raise Reject(f'Stale request: expected {request["expected_sha"]}, current PR HEAD is {head}.')
        inputs = {**request, 'pr_number': str(pr_number), 'expected_sha': head,
                  'source_sha': head, 'request_id': request_id,
                  'selector_key': hashlib.sha256(request['test_selector'].encode('utf-8')).hexdigest()[:16] if request['test_selector'] else '-'}
        url, status = dispatch(repo, event['repository']['default_branch'], inputs, rerun)
    except Reject as error:
        reply(repo, pr_number, f'<!-- jlmod-ci:{request_id} -->\nRequest `{request_id}` was not confirmed: {error}')
        raise
    body = (f'<!-- jlmod-ci:{request_id} -->\n{status}\n\n'
            f'{url}\n\nPR {pr_number}; profile `{inputs["mode"]}`; source `{head}`; '
            f'selector `{inputs["test_selector"] or "none"}`; request `{request_id}`. '
            'Read the run logs/summary for the result; dispatch is not a passing test verdict.')
    reply(repo, pr_number, body)
    return url


def reply(repo, pr_number, body):
    print(body)
    # A failed reply must never cause another dispatch, including controller reruns.
    try:
        api(f'repos/{repo}/issues/{pr_number}/comments', 'POST', {'body': body})
    except Reject as error:
        print(f'Result reply failed; use the status above: {error}', file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True, type=Path)
    args = parser.parse_args()
    try:
        process(json.loads(args.event.read_text(encoding='utf-8')),
                os.environ.get('JLMOD_CI_ACTOR_IDS', ''),
                int(os.environ.get('GITHUB_RUN_ATTEMPT', '1')) > 1)
    except (Reject, OSError, ValueError, KeyError) as error:
        print(f'CI request rejected: {error}', file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == '__main__':
    main()
