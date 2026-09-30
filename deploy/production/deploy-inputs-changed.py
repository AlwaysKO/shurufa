#!/usr/bin/env python3
"""Exit 0 for changed release inputs, 1 for unchanged, 2 for comparison errors."""
import json
from pathlib import PurePosixPath
import subprocess
import sys


def inputs_changed(repo, baseline, target):
    def git(*args):
        return subprocess.check_output(['git', '-C', repo, *args])

    # Resolve both objects first; missing history must never be treated as unchanged.
    baseline = git('rev-parse', '--verify', baseline + '^{commit}').decode().strip()
    target = git('rev-parse', '--verify', target + '^{commit}').decode().strip()

    def changed(paths):
        result = subprocess.run(['git', '-C', repo, 'diff', '--quiet', '--no-ext-diff',
                                 baseline, target, '--', *(':(literal)' + p for p in paths)])
        if result.returncode not in (0, 1):
            raise RuntimeError('Git diff failed')
        return result.returncode == 1

    if changed(['server', 'client', 'assets']):
        return True
    # The unchanged manifest can also reference approved originals outside those trees.
    manifest = 'assets/expression/approved-keyword-gifs.json'
    if not git('ls-tree', target, '--', manifest):
        return False
    data = json.loads(git('show', f'{target}:{manifest}'))
    paths = []
    for item in data['items']:
        source = item['sourceGif']
        path = PurePosixPath(source)
        if (path.is_absolute() or '..' in path.parts or path.suffix != '.gif'
                or not source.startswith(('artifacts/', 'server/images/'))):
            raise ValueError('Invalid approved GIF path')
        paths.append(source)
    return bool(paths) and changed(paths)


if __name__ == '__main__':
    try:
        changed = inputs_changed(*sys.argv[1:])
    except Exception as error:
        print(f'Cannot compare backend inputs: {error}', file=sys.stderr)
        sys.exit(2)
    sys.exit(0 if changed else 1)
