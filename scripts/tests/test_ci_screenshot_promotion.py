# SPDX-License-Identifier: Apache-2.0

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import os
import shutil
import tempfile
import unittest
import zipfile
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("promotion", SCRIPTS / "ci-screenshot-promotion.py")
promotion = importlib.util.module_from_spec(spec)
spec.loader.exec_module(promotion)


class ScreenshotPromotionTest(unittest.TestCase):
    def setUp(self):
        self.path = promotion.REFERENCE_PREFIX + "suite/example.png"
        self.request = {
            "head_sha": "1" * 40,
            "base_sha": "2" * 40,
            "build_sha": "3" * 40,
            "run_id": 10,
            "artifact_id": 20,
            "artifact_digest": "sha256:" + "4" * 64,
        }
        self.before = promotion.PNG + b"before"
        self.after = promotion.PNG + b"after"

    def command(self, payload=None):
        return promotion.COMMAND + "\n" + json.dumps(payload or self.request)

    def write_bundle(self, target, *, context_change=None, extra=None):
        context = {
            "pr_number": "7",
            "run_attempt": "1",
            "schema_version": 2,
            "generation": "comparison",
            "changes": [{"path": self.path, "operation": "replace",
                         "before_sha256": hashlib.sha256(self.before).hexdigest() if self.before else None,
                         "after_sha256": hashlib.sha256(self.after).hexdigest() if self.after else None}],
            "build_commit": self.request["build_sha"],
            "pr_head_commit": self.request["head_sha"],
            "pr_base_commit": self.request["base_sha"],
            "run_id": str(self.request["run_id"]),
            "candidate_paths": [self.path],
            "excluded_failures": [],
        }
        if context_change:
            context_change(context)
        relative = self.path[len(promotion.REFERENCE_PREFIX):]
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr("source-context.json", json.dumps(context))
            if self.before is not None:
                archive.writestr(f"before/{relative}", self.before)
            if self.after is not None:
                archive.writestr(f"after/{relative}", self.after)
            for name, data in extra or []:
                archive.writestr(name, data)

    def test_parse_request_accepts_exact_contract(self):
        self.assertEqual(self.request, promotion.parse_request(self.command()))

    def test_parse_request_rejects_extra_key(self):
        payload = dict(self.request, unexpected=True)
        with self.assertRaises(promotion.Reject):
            promotion.parse_request(self.command(payload))

    def test_safe_bundle_accepts_exact_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "bundle.zip"
            self.write_bundle(archive)
            paths, files = promotion.safe_bundle(archive, self.request)
            self.assertEqual([self.path], paths)
            self.assertEqual(self.after, files[0][2])

    def test_safe_bundle_rejects_stale_head(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "bundle.zip"
            self.write_bundle(archive, context_change=lambda value: value.update(pr_head_commit="5" * 40))
            with self.assertRaises(promotion.Reject):
                promotion.safe_bundle(archive, self.request)

    def test_safe_bundle_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "bundle.zip"
            self.write_bundle(archive, extra=[("../escape.png", self.after)])
            with self.assertRaises(promotion.Reject):
                promotion.safe_bundle(archive, self.request)

    def test_manual_add_and_delete_have_exact_optional_sides(self):
        for operation in ('add', 'delete'):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as directory:
                self.before = None if operation == 'add' else promotion.PNG + b'before'
                self.after = None if operation == 'delete' else promotion.PNG + b'after'
                archive = Path(directory) / 'bundle.zip'
                def change(context):
                    context['generation'] = 'manual'
                    context['changes'][0]['operation'] = operation
                self.write_bundle(archive, context_change=change)
                _, files = promotion.safe_bundle(archive, self.request)
                self.assertEqual((self.path, self.before, self.after), files[0])

    def test_manifest_rejects_tampering_and_noncomparison_failures(self):
        mutations = [
            lambda c: c.update(schema_version=1),
            lambda c: c.update(excluded_failures=['render failed']),
            lambda c: c['changes'][0].update(after_sha256='0'*64),
            lambda c: c['changes'][0].update(operation='add'),
            lambda c: c.update(changes=[]),
            lambda c: c.update(candidate_paths=[None]),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                archive = Path(directory) / 'bundle.zip'
                self.write_bundle(archive, context_change=mutation)
                with self.assertRaises(promotion.Reject):
                    promotion.safe_bundle(archive, self.request)

    def test_whole_current_suite_can_be_reviewed(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory)/'bundle.zip'
            self.write_bundle(archive)
            with zipfile.ZipFile(archive) as original:
                context = json.loads(original.read('source-context.json'))
            context['candidate_paths'] = [promotion.REFERENCE_PREFIX+f'suite/{i}.png' for i in range(122)]
            context['changes'] = [dict(context['changes'][0], path=path) for path in context['candidate_paths']]
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr('source-context.json', json.dumps(context))
                for i in range(122):
                    output.writestr(f'before/suite/{i}.png', self.before)
                    output.writestr(f'after/suite/{i}.png', self.after)
            self.assertEqual(122, len(promotion.safe_bundle(archive, self.request)[0]))

    def test_inspect_binds_manual_and_pr_artifacts_to_owner_run_and_exact_pr(self):
        scenarios = ['pull_request', 'workflow_dispatch', 'branch-comparison', 'trusted-manual', 'trusted-comparison',
                     'wrong-source-title', 'wrong-default-branch', 'wrong-artifact-revision',
                     'stale-base', 'wrong-attempt', 'wrong-workflow', 'wrong-owner', 'tampered-zip']
        for scenario in scenarios:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                trusted = scenario.startswith('trusted-') or scenario.startswith('wrong-source-') or scenario in ('wrong-default-branch', 'wrong-artifact-revision')
                dispatched = trusted or scenario in ('workflow_dispatch', 'branch-comparison')
                manual = dispatched and scenario not in ('trusted-comparison', 'branch-comparison')
                self.request['build_sha'] = self.request['head_sha'] if dispatched else '3'*40
                archive = root/'source.zip'
                self.write_bundle(archive, context_change=lambda c: c.update(generation='manual' if manual else 'comparison'))
                self.request['artifact_digest'] = 'sha256:'+hashlib.sha256(archive.read_bytes()).hexdigest()
                if scenario == 'tampered-zip':
                    self.request['artifact_digest'] = 'sha256:'+'0'*64
                event = dict(issue=dict(number=7, pull_request={"url":"fixture"}), sender=dict(id=2 if scenario=='wrong-owner' else 1),
                             repository=dict(full_name='owner/repo', owner=dict(id=1), default_branch='alpha'), comment=dict(body=self.command()))
                event_path = root/'event.json'
                event_path.write_text(json.dumps(event))
                pr = dict(state='open', head=dict(sha=self.request['head_sha'], ref='topic', repo=dict(full_name='owner/repo')),
                          base=dict(sha='5'*40 if scenario=='stale-base' else self.request['base_sha']))
                workflow_sha = '6'*40 if trusted else self.request['head_sha']
                title_sha = '7'*40 if scenario == 'wrong-source-title' else self.request['head_sha']
                run = dict(name='Android CI', event='workflow_dispatch' if dispatched else 'pull_request',
                           head_sha=workflow_sha, head_repository=dict(full_name='owner/repo'),
                           head_branch='other' if scenario == 'wrong-default-branch' else 'alpha',
                           display_title=f"PR 7 | {'update-screenshots' if manual else 'screenshots'} | {title_sha} | test - | request comment-123",
                           path='.github/workflows/other.yml' if scenario=='wrong-workflow' else '.github/workflows/android.yml',
                           status='completed', conclusion='success' if manual else 'failure', run_attempt=2 if scenario=='wrong-attempt' else 1)
                artifact = dict(name=promotion.ARTIFACT_NAME, expired=False, digest=self.request['artifact_digest'],
                                workflow_run=dict(id=10, head_sha='9'*40 if scenario == 'wrong-artifact-revision' else workflow_sha))
                def gh(path):
                    if '/pulls/' in path: return pr
                    if '/actions/runs/' in path: return run
                    if '/actions/artifacts/' in path: return artifact
                    return dict(parents=[dict(sha=self.request['base_sha']),dict(sha=self.request['head_sha'])])
                def download(*args, binary_stdout):
                    with archive.open('rb') as source:
                        shutil.copyfileobj(source, binary_stdout)
                args = argparse.Namespace(event=event_path, artifact=root/'download.zip', state=root/'state.json')
                with mock.patch.object(promotion, 'gh', side_effect=gh), mock.patch.object(promotion, 'call', side_effect=download), mock.patch.dict(os.environ, GITHUB_REPOSITORY='owner/repo', GITHUB_OUTPUT=str(root/'output')):
                    if scenario in ('pull_request','workflow_dispatch', 'branch-comparison', 'trusted-manual', 'trusted-comparison'):
                        promotion.inspect(args)
                        self.assertEqual([self.path], json.loads(args.state.read_text())['paths'])
                    else:
                        with self.assertRaises(promotion.Reject):
                            promotion.inspect(args)

    def test_dispatch_failure_preserves_promoted_sha_and_recovery_without_accepting_again(self):
        promoted = '8'*40
        state = dict(repo='owner/repo', pr=7, request=self.request, paths=[self.path], default_branch='alpha')
        calls = []
        def gh(path, method='GET', payload=None):
            calls.append((path, method, payload))
            if '/pulls/' in path:
                return dict(head=dict(sha=promoted))
            if path.endswith('/dispatches'):
                raise promotion.Reject('network response lost')
            return dict(id=999)
        with tempfile.TemporaryDirectory() as directory:
            state_path = Path(directory)/'state.json'
            summary_path = Path(directory)/'summary.md'
            with mock.patch.object(promotion, 'gh', side_effect=gh), mock.patch.dict(os.environ, GITHUB_STEP_SUMMARY=str(summary_path)):
                with self.assertRaisesRegex(promotion.Reject, f'Partial success.*{promoted}'):
                    promotion.finish_promotion(state_path, state, promoted)
            result = json.loads(state_path.read_text())['promotion_result']
            self.assertEqual('partial-success', result['status'])
            self.assertEqual(promoted, result['promoted_sha'])
            dispatch = next(call for call in calls if call[0].endswith('/dispatches'))
            self.assertEqual('alpha', dispatch[2]['ref'])
            self.assertEqual(promoted, dispatch[2]['inputs']['expected_sha'])
            self.assertEqual(promoted, dispatch[2]['inputs']['source_sha'])
            summary = summary_path.read_text()
            self.assertIn(f'/jlmod-ci validate --sha {promoted}', summary)
            self.assertIn('Do not accept the old candidate again', summary)

    def test_reply_failure_does_not_dispatch_again_after_successful_push(self):
        promoted = '8'*40
        state = dict(repo='owner/repo', pr=7, request=self.request, paths=[self.path], default_branch='alpha')
        dispatches = []
        def gh(path, method='GET', payload=None):
            if '/pulls/' in path:
                return dict(head=dict(sha=promoted))
            if path.endswith('/dispatches'):
                dispatches.append(payload)
                return dict(workflow_run_id=123, html_url='https://github.com/owner/repo/actions/runs/123')
            raise promotion.Reject('reply unavailable')
        with tempfile.TemporaryDirectory() as directory:
            state_path = Path(directory)/'state.json'
            with mock.patch.object(promotion, 'gh', side_effect=gh):
                promotion.finish_promotion(state_path, state, promoted)
            self.assertEqual(1, len(dispatches))
            result = json.loads(state_path.read_text())['promotion_result']
            self.assertEqual('validation-dispatched', result['status'])
            self.assertEqual(123, result['run_id'])


if __name__ == "__main__":
    unittest.main()
