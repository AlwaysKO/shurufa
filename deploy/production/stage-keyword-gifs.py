#!/usr/bin/env python3
"""Stage approved GIFs from the exact Git revision being deployed."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys


def stage(repo, revision, release):
    release = Path(release).resolve()
    catalog = release / 'assets/expression/approved-keyword-gifs.json'
    if not catalog.exists():
        return 0
    pending = {}
    for item in json.loads(catalog.read_text())['items']:
        source = item['sourceGif']
        path = PurePosixPath(source)
        if (path.is_absolute() or '..' in path.parts or path.suffix != '.gif'
                or not source.startswith(('artifacts/', 'server/images/'))):
            raise ValueError(f'Invalid keyword GIF path: {source}')
        destination = release / source
        if not destination.resolve().is_relative_to(release):
            raise ValueError(f'Keyword GIF escapes release: {source}')
        content = subprocess.check_output(['git', '-C', str(repo), 'show', f'{revision}:{source}'])
        if hashlib.sha256(content).hexdigest() != item['sha256']:
            raise ValueError(f'Keyword GIF checksum mismatch: {source}')
        if destination.exists() and destination.read_bytes() != content:
            raise ValueError(f'Conflicting keyword GIF: {source}')
        pending[destination] = content
    for destination, content in pending.items():
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
    return len(pending)


if __name__ == '__main__':
    print(f'Staged {stage(*sys.argv[1:])} approved keyword GIFs')
