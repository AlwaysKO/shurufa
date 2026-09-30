#!/usr/bin/env bash
# 仅启动本次创建的独立 PostgreSQL 测试实例，不读取/操作开发或线上数据库。
set -euo pipefail
cd "$(dirname "$0")/.."
PG_BIN="${PG_BIN:-$(pg_config --bindir)}"
ROOT=$(mktemp -d /tmp/shurufa-call-audio.XXXXXX)
cleanup() {
  "$PG_BIN/pg_ctl" -D "$ROOT/data" -m fast stop >/dev/null 2>&1 || true
  rm -rf -- "$ROOT"
}
trap cleanup EXIT
mkdir "$ROOT/socket"
printf 'call-audio-only' > "$ROOT/test-instance-only"
"$PG_BIN/initdb" -D "$ROOT/data" -A trust --no-locale >/dev/null
"$PG_BIN/pg_ctl" -D "$ROOT/data" -l "$ROOT/postgres.log" -o "-k $ROOT/socket -h ''" start >/dev/null
"$PG_BIN/createdb" -h "$ROOT/socket" -U "$(id -un)" call_audio_test
CALL_RECORDING_TEST_CLUSTER="$ROOT" npx --no-install vitest run src/api/callRecordings.test.ts src/calls/storage.test.ts src/api/callRecordingsUi.test.ts
