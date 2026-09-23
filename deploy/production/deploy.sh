#!/usr/bin/env bash
set -Eeuo pipefail
export PATH=/home/ubuntu/.local/node22/bin:/usr/local/bin:/usr/bin:/bin
export GIT_SSH_COMMAND='ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=10 -o ServerAliveCountMax=2'
base=/data/phpwww/shurufa
repo=/home/ubuntu/shurufa-app
config=/home/ubuntu/shurufa-deploy
exec 9>"$config/deploy.lock"
flock -n 9 || exit 0
git -C "$repo" fetch --quiet origin main
sha=$(git -C "$repo" rev-parse origin/main)
if [[ -f "$base/current/REVISION" && $(cat "$base/current/REVISION") == "$sha" && ${1:-} != --force ]]; then
  echo "Already deployed $sha"
  exit 0
fi
release="$base/releases/$(date -u +%Y%m%dT%H%M%S)-${sha:0:12}"
mkdir -p "$release"
echo "Building $sha in $release"
# Runtime uploads are shared. Only manifest-listed recommendation GIFs are staged separately.
git -C "$repo" archive "$sha" server client assets | tar --exclude='server/uploads' -x -C "$release"
python3 "$config/stage-keyword-gifs.py" "$repo" "$sha" "$release"
printf '%s\n' "$sha" > "$release/REVISION"
mkdir -p "$base/shared/uploads"
ln -sT "$base/shared/uploads" "$release/server/uploads"
cd "$release/server"
npm ci --no-audit --no-fund
npm run build
npm run expression:generate
if [[ -f scripts/stage-sticker-bundle.mjs ]]; then
  node scripts/stage-sticker-bundle.mjs "$repo" "$sha" "$base/shared/uploads"
fi
cd "$release/client"
npm ci --no-audit --no-fund
npm run build
set -a
source "$config/production.env"
set +a
umask 077
pg_dump -Fc -f "$base/backups/$(basename "$release").dump"
umask 0022
cd "$release/server"
node "$config/migrate.mjs"
if [[ -f scripts/stage-sticker-bundle.mjs ]]; then
  node dist/stickers/cli.js import
fi
previous=$(readlink -f "$base/current" || true)
ln -s "$release" "$base/current.next"
mv -Tf "$base/current.next" "$base/current"
rollback() {
  echo 'New release failed; restoring previous application release' >&2
  if [[ -n "$previous" && -d "$previous" ]]; then
    ln -s "$previous" "$base/current.next"
    mv -Tf "$base/current.next" "$base/current"
    sudo -n systemctl restart shurufa.service
  else
    sudo -n systemctl stop shurufa.service
  fi
  exit 1
}
sudo -n systemctl restart shurufa.service || rollback
healthy=false
for attempt in {1..30}; do
  if node "$config/healthcheck.mjs"; then
    healthy=true
    break
  fi
  sleep 1
done
[[ "$healthy" == true ]] || rollback
git -C "$repo" reset --hard "$sha"
echo "Deployed $sha successfully"
# Keep the latest five successful release directories and database backups.
python3 - "$base" "$release" "$previous" <<'PY'
import pathlib,shutil,sys
base=pathlib.Path(sys.argv[1])
keep=set(sys.argv[2:])
for p in sorted((base/'releases').iterdir(),reverse=True)[5:]:
    if str(p) not in keep: shutil.rmtree(p)
for p in sorted((base/'backups').glob('*.dump'),reverse=True)[5:]: p.unlink()
PY
