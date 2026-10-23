# SPDX-License-Identifier: Apache-2.0
import copy
import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('ci_commands', SCRIPTS / 'ci-commands.py')
commands = importlib.util.module_from_spec(spec)
spec.loader.exec_module(commands)
REPO = 'owner/project'
SHA = 'a' * 40
INPUTS = dict(mode='unit', pr_number='42', expected_sha=SHA, source_sha=SHA,
              request_id='comment-123', test_selector='package.NewRegressionTest',
              selector_key=hashlib.sha256(b'package.NewRegressionTest').hexdigest()[:16])
EVENT = dict(action='created', sender=dict(id=7),
             repository=dict(full_name=REPO, owner=dict(id=7), default_branch='alpha'),
             issue=dict(number=42, pull_request=dict(url='pr')),
             comment=dict(id=123, body='/jlmod-ci unit --test package.NewRegressionTest', user=dict(id=7)))
PR = dict(state='open', head=dict(sha=SHA, repo=dict(full_name=REPO)),
          base=dict(repo=dict(full_name=REPO)))
RUN = dict(path='.github/workflows/android.yml', head_branch='alpha',
           head_repository=dict(full_name=REPO), status='in_progress',
           display_title=commands.title(INPUTS), html_url='https://github.com/owner/project/actions/runs/9')


class CICommandsTest(unittest.TestCase):
    def test_deliberate_commands_and_new_class_method_selectors(self):
        for profile in commands.PROFILES:
            suffix = " --test 'new.package.NewTest'" if profile in ('unit', 'instrumentation') else ''
            request = commands.parse_request(f'/jlmod-ci {profile}{suffix} --sha {SHA}')
            self.assertEqual(profile, request['mode'])
            self.assertEqual(SHA, request['expected_sha'])
        self.assertEqual('new.package.NewTest#regression', commands.parse_request(
            '/jlmod-ci instrumentation --test new.package.NewTest#regression')['test_selector'])
        self.assertEqual('new.package.NewTest.regression', commands.parse_request(
            '/jlmod-ci unit --test new.package.NewTest.regression')['test_selector'])

    def test_quotes_examples_multiline_and_injection_are_not_commands(self):
        invalid = ['Discuss /jlmod-ci validate', '> /jlmod-ci validate',
                   '`/jlmod-ci validate`', '```\n/jlmod-ci validate\n```',
                   '/jlmod-ci validate\nplease', '/jlmod-ci validate\rplease',
                   ' /jlmod-ci validate', '/jlmod-ci validate ',
                   '/jlmod-ci validate ordinary discussion', '/jlmod-ci unit',
                   '/jlmod-ci unit --test --info', '/jlmod-ci unit --test p.T;id',
                   '/jlmod-ci unit --test "p.T$(id)"', '/jlmod-ci unit --test p.T --test p.X',
                   '/jlmod-ci instrumentation --test p.Test --task :app:publish',
                   '/jlmod-ci validate --test p.T', '/jlmod-ci validate --sha short']
        for body in invalid:
            with self.subTest(body=body), self.assertRaises(commands.Reject):
                commands.parse_request(body)

    def test_policy_owner_explicit_id_and_no_implicit_bot_or_issue_authority(self):
        self.assertTrue(commands.allowed(EVENT, ''))
        event = copy.deepcopy(EVENT)
        event['sender']['id'] = event['comment']['user']['id'] = 12
        self.assertTrue(commands.allowed(event, '|12|99|'))
        for value in ('', '*', 'all', '12', '|112|', '[12]', '|true|', 'garbage'):
            self.assertFalse(commands.allowed(event, value), value)
        for action in ('edited', 'deleted'):
            event['action'] = action
            self.assertFalse(commands.allowed(event, '|12|'))
        event = copy.deepcopy(EVENT)
        event['issue'].pop('pull_request')
        self.assertFalse(commands.allowed(event, '|7|'))
        event = copy.deepcopy(EVENT)
        event['comment']['user']['id'] = 99
        self.assertFalse(commands.allowed(event, ''))

    @patch.dict(os.environ, GITHUB_REPOSITORY=REPO)
    def test_dispatch_uses_default_workflow_and_exact_pr_revision(self):
        calls = []
        def api(path, method='GET', payload=None):
            calls.append((path, method, payload))
            if '/pulls/' in path:
                return PR
            if '/runs?' in path:
                return {'workflow_runs': []}
            if path.endswith('/dispatches'):
                return {'workflow_run_id': 9, 'html_url': RUN['html_url']}
            return {}
        with patch.object(commands, 'api', side_effect=api):
            self.assertEqual(RUN['html_url'], commands.process(EVENT, ''))
        posted = [payload for path, method, payload in calls if path.endswith('/dispatches')]
        self.assertEqual([dict(ref='alpha', inputs=INPUTS)], posted)
        self.assertEqual(1, len([path for path, _, _ in calls if path.endswith('/comments')]))

    def test_existing_request_and_identical_active_run_are_reused(self):
        for request_id, status, expected in [('comment-123', 'completed', True),
                                              ('comment-456', 'queued', True),
                                              ('comment-456', 'completed', False)]:
            run = {**RUN, 'display_title': commands.title({**INPUTS, 'request_id': request_id}), 'status': status}
            with patch.object(commands, 'api', return_value={'workflow_runs': [run]}):
                self.assertEqual(expected, commands.find_run(REPO, INPUTS, 'alpha') is not None)
        for changed in ({'head_branch': 'untrusted-branch'}, {'path': '.github/workflows/other.yml'},
                        {'head_repository': {'full_name': 'fork/project'}},
                        {'display_title': commands.title({**INPUTS, 'expected_sha': 'b'*40})}):
            with patch.object(commands, 'api', return_value={'workflow_runs': [{**RUN, **changed}]}):
                self.assertIsNone(commands.find_run(REPO, INPUTS, 'alpha'))

    def test_lost_dispatch_response_recovers_exact_request_without_second_post(self):
        calls = []
        def api(path, method='GET', payload=None):
            calls.append(method)
            if method == 'POST':
                raise commands.Reject('connection lost after POST')
            return {'workflow_runs': [] if len(calls) == 1 else [RUN]}
        with patch.object(commands, 'api', side_effect=api):
            self.assertEqual(RUN['html_url'], commands.dispatch(REPO, 'alpha', INPUTS)[0])
        self.assertEqual(1, calls.count('POST'))
        with patch.object(commands, 'api', return_value={'workflow_runs': []}) as api:
            with self.assertRaisesRegex(commands.Reject, 'controller retry'):
                commands.dispatch(REPO, 'alpha', INPUTS, rerun=True)
        self.assertTrue(all(call.args[0].find('/dispatches') < 0 for call in api.call_args_list))

    @patch.dict(os.environ, GITHUB_REPOSITORY=REPO)
    def test_reply_failure_does_not_retry_dispatch(self):
        def api(path, method='GET', payload=None):
            if '/pulls/' in path:
                return PR
            if '/runs?' in path:
                return {'workflow_runs': []}
            if path.endswith('/comments'):
                raise commands.Reject('reply unavailable')
            return {'workflow_run_id': 9, 'html_url': RUN['html_url']}
        with patch.object(commands, 'api', side_effect=api) as mock:
            self.assertEqual(RUN['html_url'], commands.process(EVENT, ''))
        self.assertEqual(1, sum(c.args[0].endswith('/dispatches') for c in mock.call_args_list))

    @patch.dict(os.environ, GITHUB_REPOSITORY=REPO)
    def test_stale_and_fork_requests_get_status_without_dispatch(self):
        event = copy.deepcopy(EVENT)
        event['comment']['body'] += ' --sha ' + 'b'*40
        for pr, value in [(PR, event), ({**PR, 'head': {'sha': SHA, 'repo': {'full_name': 'fork/project'}}}, EVENT)]:
            with patch.object(commands, 'api', side_effect=[pr, {}]) as api:
                with self.assertRaises(commands.Reject):
                    commands.process(value, '')
            self.assertFalse(any(c.args[0].endswith('/dispatches') for c in api.call_args_list))
            self.assertEqual(1, sum(c.args[0].endswith('/comments') for c in api.call_args_list))

    def test_unauthorized_events_do_no_api_work(self):
        event = copy.deepcopy(EVENT)
        event['issue'].pop('pull_request')
        with patch.object(commands, 'api') as api:
            with self.assertRaises(commands.Reject):
                commands.process(event, '')
            api.assert_not_called()


if __name__ == '__main__':
    unittest.main()
