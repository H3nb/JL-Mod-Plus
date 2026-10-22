# SPDX-License-Identifier: Apache-2.0
"""Exercise real Git binary patches and promotion without network or Android."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / (name+'.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


candidates = module('ci-screenshot-candidates')
promotion = module('ci-screenshot-promotion')


class ScreenshotRoundtripTest(unittest.TestCase):
    def test_manual_bundle_patch_and_promotion_preserve_exact_add_replace_delete_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root).decode().strip()
            git('init', '-q')
            git('config', 'user.name', 'Test')
            git('config', 'user.email', 'test@example.invalid')
            references = root / candidates.REFERENCES
            references.mkdir(parents=True)
            (references/'replace space.png').write_bytes(candidates.PNG_SIGNATURE+b'old\x00')
            (references/'delete.png').write_bytes(candidates.PNG_SIGNATURE+b'delete\x00')
            git('add', '.')
            git('commit', '-qm', 'fixture')
            head = git('rev-parse', 'HEAD')
            context = root/'context.json'
            context.write_text(json.dumps(dict(pr_head_commit=head, pr_base_commit='2'*40, pr_number='10')))
            (references/'replace space.png').write_bytes(candidates.PNG_SIGNATURE+b'new\x00')
            (references/'delete.png').unlink()
            (references/'add.png').write_bytes(candidates.PNG_SIGNATURE+b'added\x00')
            old_cwd = Path.cwd()
            try:
                os.chdir(root)
                with mock.patch.dict(os.environ, GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1'):
                    candidates.package_updated(context)
            finally:
                os.chdir(old_cwd)
            manifest = json.loads((root/candidates.OUTPUT/'source-context.json').read_text())
            self.assertEqual({'add','replace','delete'}, {item['operation'] for item in manifest['changes']})
            self.assertEqual('', git('diff', '--cached', '--name-only'))
            archive = root/'bundle.zip'
            with zipfile.ZipFile(archive, 'w') as output:
                for path in (root/candidates.OUTPUT).rglob('*'):
                    if path.is_file():
                        output.write(path, path.relative_to(root/candidates.OUTPUT))
            request = dict(head_sha=head, base_sha='2'*40, build_sha=head, run_id=42, artifact_id=5, artifact_digest='sha256:'+'0'*64)
            paths, files = promotion.safe_bundle(archive, request)
            expected = {path: after for path, _, after in files}
            # Restore this disposable fixture and use the very same binary patch a normal checkout uses.
            git('restore', '.')
            (references/'add.png').unlink()
            patch = str(root/candidates.OUTPUT/'baselines.patch')
            git('apply', '--check', patch)
            git('apply', patch)
            for path, after in expected.items():
                self.assertEqual(after, (root/path).read_bytes() if (root/path).exists() else None)
            git('restore', '.')
            (references/'add.png').unlink()
            # The connector promotion path must produce identical bytes with no real push/API writes.
            target = root/'checkout'
            subprocess.run(['git','clone','-q',str(root),str(target)], check=True)
            state = root/'state.json'
            state.write_text(json.dumps(dict(request=request, repo='owner/repo', pr=10, branch='fixture', paths=paths)))
            calls = []
            real_call = promotion.call
            def call(*args, **kwargs):
                if args[:2] == ('git', 'push'):
                    calls.append(args)
                    return ''
                return real_call(*args, **kwargs)
            def gh(path, method='GET', payload=None):
                if method != 'GET':
                    calls.append((path, method, payload))
                    return None
                current = subprocess.check_output(['git','rev-parse','HEAD'], cwd=target, text=True).strip()
                return dict(state='open', head=dict(sha=current, ref='fixture'), base=dict(sha='2'*40))
            with mock.patch.object(promotion,'call',side_effect=call), mock.patch.object(promotion,'gh',side_effect=gh):
                promotion.promote(argparse.Namespace(state=state, artifact=archive, target=target))
            for path, after in expected.items():
                self.assertEqual(after, (target/path).read_bytes() if (target/path).exists() else None)
            self.assertEqual(head, subprocess.check_output(['git','rev-parse','HEAD^'], cwd=target, text=True).strip())
            self.assertTrue(any(item[:2] == ('git','push') for item in calls))
            self.assertTrue(any('dispatches' in item[0] for item in calls))
