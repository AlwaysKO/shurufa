#!/usr/bin/env python3
"""Exercise deployment decisions with real temporary Git history, no services/DB."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


class DeployInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / 'repo'
        self.repo.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'deploy-test')
        self.git('config', 'user.email', 'deploy-test@localhost')
        self.write('server/src/index.ts', 'server')
        self.write('client/src/main.ts', 'client')
        self.write('android/app.kt', 'android')
        self.write('assets/expression/approved-keyword-gifs.json', json.dumps({
            'items': [{'sourceGif': 'artifacts/published/a.gif', 'sha256': 'unused'}]
        }))
        self.write('artifacts/published/a.gif', 'gif')
        self.base = self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True).strip()

    def write(self, path, text):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', '测试提交')
        return self.git('rev-parse', 'HEAD')

    def decision(self, base=None):
        return subprocess.run(['python3', str(HERE / 'deploy-inputs-changed.py'),
                               str(self.repo), base or self.base, self.git('rev-parse', 'HEAD')],
                              capture_output=True, text=True)

    def test_android_docs_diagnostics_and_deploy_archive_skip(self):
        for path in ('android/app.kt', 'docs/notes.md', 'AGENTS.md',
                     'artifacts/diagnostics/test.txt', 'deploy/production/deploy.sh'):
            self.write(path, 'new')
        self.commit()
        self.assertEqual(self.decision().returncode, 1)

    def test_backend_inputs_trigger_even_with_android_change(self):
        for path in ('server/src/index.ts', 'client/src/main.ts',
                     'server/package-lock.json', 'server/sql/001.sql',
                     'assets/expression/manifest.source.json', 'artifacts/published/a.gif'):
            with self.subTest(path=path):
                self.git('reset', '--hard', self.base)
                self.write(path, 'changed')
                self.write('android/app.kt', 'changed')
                self.commit()
                self.assertEqual(self.decision().returncode, 0)

    def test_unpublished_backend_change_survives_later_android_commit(self):
        self.write('server/src/index.ts', 'new backend')
        self.commit()
        self.write('android/app.kt', 'new android')
        self.commit()
        self.assertEqual(self.decision().returncode, 0)

    def test_delete_and_move_out_of_backend_trigger(self):
        self.git('mv', 'server/src/index.ts', 'android/moved.kt')
        self.commit()
        self.assertEqual(self.decision().returncode, 0)

    def test_same_backend_tree_after_revert_skips(self):
        self.write('server/src/index.ts', 'temporary')
        self.commit()
        self.write('server/src/index.ts', 'server')
        self.write('android/app.kt', 'new')
        self.commit()
        self.assertEqual(self.decision().returncode, 1)

    def test_missing_baseline_is_error_not_skip(self):
        self.assertEqual(self.decision('0' * 40).returncode, 2)

    def test_invalid_manifest_is_error_not_skip(self):
        self.write('assets/expression/approved-keyword-gifs.json', 'broken')
        baseline = self.commit()
        self.write('android/app.kt', 'new')
        self.commit()
        self.assertEqual(self.decision(baseline).returncode, 2)

    def test_absent_optional_manifest_still_skips_android(self):
        self.git('rm', 'assets/expression/approved-keyword-gifs.json')
        baseline = self.commit()
        self.write('android/app.kt', 'new')
        self.commit()
        self.assertEqual(self.decision(baseline).returncode, 1)

    def run_gate(self, revision, force=False):
        # Execute the actual production prefix; isolate paths and stub only lock/fetch.
        root = Path(self.temp.name)
        config, base = root / 'config', root / 'base'
        config.mkdir(exist_ok=True)
        (base / 'current').mkdir(parents=True, exist_ok=True)
        (config / 'deploy-inputs-changed.py').write_bytes((HERE / 'deploy-inputs-changed.py').read_bytes())
        if revision is not None:
            (base / 'current/REVISION').write_text(revision + '\n')
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')
        script = (HERE / 'deploy.sh').read_text().split('release="$base/releases/')[0]
        script = script.replace('base=/data/phpwww/shurufa', f'base="{base}"')
        script = script.replace('repo=/home/ubuntu/shurufa-app', f'repo="{self.repo}"')
        script = script.replace('config=/home/ubuntu/shurufa-deploy', f'config="{config}"')
        script = script.replace('flock -n 9 || exit 0', ': # isolated test, no competing deploy')
        script = script.replace('git -C "$repo" fetch --quiet origin main', ': # local origin ref already set')
        script += '\nprintf "DEPLOY_REQUIRED\\n"\n'
        result = subprocess.run(['bash', '-c', script, 'test', *(['--force'] if force else [])],
                                text=True, capture_output=True)
        if revision is not None:
            self.assertEqual((base / 'current/REVISION').read_text(), revision + '\n')
        return result

    def test_script_skips_before_any_release_work(self):
        self.write('android/app.kt', 'new')
        self.commit()
        result = self.run_gate(self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('DEPLOY_REQUIRED', result.stdout)
        self.assertIn('No backend input changes', result.stdout)

    def test_script_force_preserves_redeploy(self):
        self.write('android/app.kt', 'new')
        self.commit()
        result = self.run_gate(self.base, force=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('DEPLOY_REQUIRED', result.stdout)

    def test_script_backend_change_still_deploys(self):
        self.write('client/src/main.ts', 'new')
        self.commit()
        result = self.run_gate(self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('DEPLOY_REQUIRED', result.stdout)

    def test_script_same_revision_skips(self):
        result = self.run_gate(self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Already deployed', result.stdout)
        self.assertNotIn('DEPLOY_REQUIRED', result.stdout)

    def test_script_first_deploy_preserved(self):
        result = self.run_gate(None)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('DEPLOY_REQUIRED', result.stdout)

    def test_script_bad_baseline_stops(self):
        result = self.run_gate('0' * 40)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertNotIn('DEPLOY_REQUIRED', result.stdout)


if __name__ == '__main__':
    unittest.main()
