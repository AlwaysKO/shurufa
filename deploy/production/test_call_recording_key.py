import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('ensure-call-recording-key.py')


class RecordingKeyDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.config = self.root / 'production.env'
        self.original = 'APP_ENV=production\nPGPASSWORD=private-test-value\n'
        self.config.write_text(self.original)
        self.shared = self.root / 'shared'
        self.shared.mkdir()
        self.key = self.shared / 'private' / 'call-recording.key'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        fake_psql = self.bin / 'psql'
        fake_psql.write_text('#!/bin/sh\nprintf "%s\\n" "$TEST_RECORDINGS_EXIST"\nexit "${TEST_QUERY_EXIT:-0}"\n')
        fake_psql.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], TEST_RECORDINGS_EXIST='f')
        self.env.pop('CALL_RECORDING_KEY_FILE', None)

    def run_script(self):
        return subprocess.run([sys.executable, str(SCRIPT), '--env-file', str(self.config), '--shared-dir', str(self.shared)],
                              env=self.env, capture_output=True, text=True)

    def existing_key(self, data=b'x' * 32, mode=0o600):
        self.key.parent.mkdir()
        self.key.write_bytes(data)
        self.key.chmod(mode)

    def test_first_deploy_initializes_once_and_backs_up_config(self):
        first = self.run_script()
        self.assertEqual(first.returncode, 0, first.stderr)
        value = self.key.read_bytes()
        self.assertEqual(len(value), 32)
        self.assertEqual(self.key.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.key.parent.stat().st_mode & 0o777, 0o700)
        self.assertIn('CALL_RECORDING_KEY_FILE=', self.config.read_text())
        config_before = self.config.read_bytes()
        backups = list(self.root.glob('production.env.before-call-recording-*'))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(), self.original)
        self.assertEqual(backups[0].stat().st_mode & 0o777, 0o600)
        second = self.run_script()
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(self.key.read_bytes(), value)
        self.assertEqual(self.config.read_bytes(), config_before)
        self.assertEqual(len(list(self.root.glob('production.env.before-call-recording-*'))), 1)
        self.assertNotIn('private-test-value', first.stdout + first.stderr + second.stdout + second.stderr)
        sourced = subprocess.run(['bash', '-c', 'set -a; source "$1"; printf "%s" "$CALL_RECORDING_KEY_FILE"', 'test', str(self.config)], capture_output=True, text=True)
        self.assertEqual(sourced.stdout, str(self.key))

    def test_existing_recordings_without_key_fail_without_mutation(self):
        self.env['TEST_RECORDINGS_EXIST'] = 't'
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('recording_history_exists_restore_original_key', result.stderr)
        self.assertFalse(self.key.exists())
        self.assertEqual(self.config.read_text(), self.original)

    def test_query_failure_never_generates_key(self):
        self.env['TEST_QUERY_EXIT'] = '2'
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('recording_history_check_failed', result.stderr)
        self.assertFalse(self.key.exists())
        self.assertEqual(self.config.read_text(), self.original)

    def test_existing_shared_key_is_reused_with_history(self):
        self.existing_key()
        self.env['TEST_RECORDINGS_EXIST'] = 't'
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.key.read_bytes(), b'x' * 32)

    def test_invalid_existing_key_is_never_replaced(self):
        self.existing_key(b'invalid')
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('invalid_existing_recording_key', result.stderr)
        self.assertEqual(self.key.read_bytes(), b'invalid')
        self.assertEqual(self.config.read_text(), self.original)

    def test_configured_existing_key_is_not_rotated_or_relocated(self):
        previous = self.root / 'previous private.key'
        previous.write_bytes(b'p' * 32)
        previous.chmod(0o600)
        self.config.write_text(self.original + f"CALL_RECORDING_KEY_FILE='{previous}'\n")
        self.env['TEST_RECORDINGS_EXIST'] = 't'
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.key.exists())
        self.assertEqual(previous.read_bytes(), b'p' * 32)

    def test_symlink_key_is_rejected(self):
        self.key.parent.mkdir()
        target = self.root / 'target'
        target.write_bytes(b'z' * 32)
        self.key.symlink_to(target)
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertEqual(target.read_bytes(), b'z' * 32)

    def test_existing_key_permissions_are_repaired_without_rotation(self):
        self.existing_key(mode=0o644)
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.key.read_bytes(), b'x' * 32)
        self.assertEqual(self.key.stat().st_mode & 0o777, 0o600)

    def test_blank_quoted_setting_can_be_initialized(self):
        self.config.write_text(self.original + "CALL_RECORDING_KEY_FILE=''\n")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.key.read_bytes()), 32)

    def test_missing_configured_key_with_history_does_not_fall_back(self):
        self.existing_key()
        missing = self.root / 'lost.key'
        self.config.write_text(self.original + f'CALL_RECORDING_KEY_FILE={missing}\n')
        self.env['TEST_RECORDINGS_EXIST'] = 't'
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('recording_history_exists_restore_original_key', result.stderr)
        self.assertFalse(missing.exists())
        self.assertEqual(self.key.read_bytes(), b'x' * 32)

    def test_unexpected_database_output_does_not_generate_key(self):
        self.env['TEST_RECORDINGS_EXIST'] = 'unexpected'
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('recording_history_check_failed', result.stderr)
        self.assertFalse(self.key.exists())

    def test_duplicate_configuration_is_rejected_without_mutation(self):
        self.config.write_text(self.original + 'CALL_RECORDING_KEY_FILE=\nCALL_RECORDING_KEY_FILE=\n')
        before = self.config.read_bytes()
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('duplicate_key_configuration', result.stderr)
        self.assertFalse(self.key.exists())
        self.assertEqual(self.config.read_bytes(), before)

    def test_concurrent_initialization_reuses_one_key_and_one_config_backup(self):
        command = [sys.executable, str(SCRIPT), '--env-file', str(self.config), '--shared-dir', str(self.shared)]
        processes = [subprocess.Popen(command, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                     for _ in range(2)]
        for process in processes:
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 0, stderr)
        self.assertEqual(len(self.key.read_bytes()), 32)
        self.assertEqual(len(list(self.root.glob('production.env.before-call-recording-*'))), 1)
        self.assertEqual(self.config.read_text().count('CALL_RECORDING_KEY_FILE='), 1)

    def test_existing_setgid_private_directory_is_supported(self):
        self.existing_key()
        self.key.parent.chmod(0o2700)
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.key.read_bytes(), b'x' * 32)
        self.assertEqual(self.key.parent.stat().st_mode & 0o777, 0o700)


if __name__ == '__main__':
    unittest.main()
