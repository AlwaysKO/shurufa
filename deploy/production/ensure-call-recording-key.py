#!/usr/bin/env python3
"""Initialize a durable recording key once; never replace an existing key."""
import argparse
import fcntl
import os
import pathlib
import re
import secrets
import shlex
import stat
import subprocess
import sys
import tempfile


class KeySetupError(Exception):
    pass


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def private_file(directory, prefix, content):
    fd, name = tempfile.mkstemp(dir=directory, prefix=prefix)
    path = pathlib.Path(name)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        path.unlink()
        raise
    return path


def configured_path(content):
    matches = re.findall(r'^\s*(?:export\s+)?CALL_RECORDING_KEY_FILE=(.*)$', content, re.MULTILINE)
    if len(matches) > 1:
        raise KeySetupError('duplicate_key_configuration')
    if not matches:
        return None
    try:
        words = shlex.split(matches[0], comments=True)
    except ValueError:
        raise KeySetupError('invalid_key_configuration') from None
    if not words or words == ['']:
        return None
    if len(words) != 1 or any(char in words[0] for char in '\n\r$`\\'):
        raise KeySetupError('invalid_key_configuration')
    path = pathlib.Path(words[0])
    if not path.is_absolute():
        raise KeySetupError('key_path_must_be_absolute')
    return path


def validate_existing(key):
    info = key.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_size != 32:
        raise KeySetupError('invalid_existing_recording_key')
    # Permissions can be repaired; the 32 key bytes must remain unchanged.
    key.chmod(0o600)
    with key.open('rb') as stream:
        if len(stream.read(33)) != 32:
            raise KeySetupError('invalid_existing_recording_key')


def ensure_key(config, shared):
    if config.is_symlink() or not config.is_file():
        raise KeySetupError('invalid_environment_file')
    content = config.read_text()
    selected = configured_path(content)
    default = shared / 'private' / 'call-recording.key'
    key = selected or default
    if key.exists() or key.is_symlink():
        validate_existing(key)
    else:
        # An unavailable database/table is not evidence that this is a new install.
        result = subprocess.run(
            ['psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c',
             'SELECT EXISTS (SELECT 1 FROM call_recording LIMIT 1);'],
            capture_output=True, text=True,
        )
        if result.returncode != 0 or result.stdout.strip() not in ('t', 'f'):
            raise KeySetupError('recording_history_check_failed')
        if result.stdout.strip() == 't':
            raise KeySetupError('recording_history_exists_restore_original_key')
        if key != default:
            raise KeySetupError('configured_recording_key_missing')
        if key.parent.is_symlink():
            raise KeySetupError('invalid_private_directory')
        key.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        key.parent.chmod(0o700)
        staged = private_file(key.parent, '.call-recording-key-', secrets.token_bytes(32))
        try:
            # Linking publishes a complete file atomically and cannot overwrite a key.
            try:
                os.link(staged, key)
            except FileExistsError:
                validate_existing(key)
            sync_directory(key.parent)
        finally:
            staged.unlink()
    config.chmod(0o600)
    if selected is None:
        # Preserve a durable private backup before adding the non-secret file path.
        private_file(config.parent, config.name + '.before-call-recording-', content.encode())
        replacement = re.sub(r'^\s*(?:export\s+)?CALL_RECORDING_KEY_FILE=.*(?:\n|$)', '', content, flags=re.MULTILINE)
        replacement = replacement.rstrip('\n') + '\nCALL_RECORDING_KEY_FILE=' + shlex.quote(str(key)) + '\n'
        staged = private_file(config.parent, '.' + config.name + '-', replacement.encode())
        try:
            os.replace(staged, config)
            sync_directory(config.parent)
        finally:
            staged.unlink(missing_ok=True)
    print('Recording encryption key ready (existing key preserved).')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', required=True, type=pathlib.Path)
    parser.add_argument('--shared-dir', required=True, type=pathlib.Path)
    args = parser.parse_args()
    # deploy.sh has its own lock; this also protects direct maintenance invocations.
    lock_path = args.env_file.parent / 'call-recording-key.lock'
    fd = os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        ensure_key(args.env_file, args.shared_dir)


if __name__ == '__main__':
    try:
        main()
    except KeySetupError as error:
        print('Recording key setup refused: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, UnicodeError):
        # Never expose database errors, environment contents, or key material.
        print('Recording key setup failed; check private file access and database availability.', file=sys.stderr)
        sys.exit(1)
