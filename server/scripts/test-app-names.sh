#!/usr/bin/env bash
# 专用临时 PostgreSQL；不读取 .env，不连接业务实例，不删除已存在的数据目录。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PG_BIN="${PG_BINDIR:-/usr/lib/postgresql/14/bin}"
CLUSTER="$(mktemp -d /tmp/shurufa-app-names.XXXXXXXX)"
printf shurufa-app-names-only > "$CLUSTER/test-instance-only"
mkdir "$CLUSTER/socket"
"$PG_BIN/initdb" -D "$CLUSTER/data" --auth=trust --no-locale --encoding=UTF8 > "$CLUSTER/initdb.log"
stop_owned_instance() {
  "$PG_BIN/pg_ctl" -D "$CLUSTER/data" -m fast -w stop > "$CLUSTER/stop.log" 2>&1 || true
  printf '\n独立测试实例已停止，产物保留于 %s\n' "$CLUSTER"
}
trap stop_owned_instance EXIT
"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -l "$CLUSTER/postgres.log" -o "-h '' -k $CLUSTER/socket -p 5432" -w start
"$PG_BIN/createdb" -h "$CLUSTER/socket" -p 5432 app_names_test
cd "$ROOT"
APP_NAMES_TEST_CLUSTER="$CLUSTER" ./server/node_modules/.bin/vitest run server/src/api/appNames.postgres.test.ts "$@"
