"""Keep native assets from four prior releases for tabs opened before deployment."""
import json
from pathlib import Path
import shutil
import sys

MANIFEST = '.native-assets.json'


def retain_assets(releases: Path, release: Path) -> int:
    dist = release / 'client' / 'dist'
    assets = dist / 'assets'
    manifest = dist / MANIFEST
    # Record this build's own files before copying, so compatibility files do not
    # accumulate forever. Re-running the same release preserves this inventory.
    if not manifest.exists():
        manifest.write_text(json.dumps(sorted(str(p.relative_to(assets))
            for p in assets.rglob('*') if p.is_file())))
    previous = sorted((p for p in releases.iterdir()
        if p.is_dir() and p != release and (p / 'REVISION').is_file()
        and (p / 'client/dist/assets').is_dir()), reverse=True)[:4]
    copied = 0
    for old in previous:
        old_dist = old / 'client/dist'
        old_assets = old_dist / 'assets'
        inventory = old_dist / MANIFEST
        names = json.loads(inventory.read_text()) if inventory.exists() else (
            str(p.relative_to(old_assets)) for p in old_assets.rglob('*') if p.is_file())
        for name in names:
            relative = Path(name)
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Invalid asset inventory path')
            source, destination = old_assets / relative, assets / relative
            if destination.exists():
                if source.read_bytes() != destination.read_bytes():
                    raise ValueError(f'Conflicting versioned asset: {relative}')
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            copied += 1
    return copied


if __name__ == '__main__':
    print(f'Retained {retain_assets(Path(sys.argv[1]), Path(sys.argv[2]))} previous frontend assets')
