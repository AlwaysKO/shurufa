#!/usr/bin/env bash
# 永不使用业务连接；独立实例和Unix socket，结束只停止本脚本新建实例。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PG_BIN="${PG_BINDIR:-/usr/lib/postgresql/14/bin}"
CLUSTER="$(mktemp -d /tmp/shurufa-page-captures.XXXXXXXX)"
printf page-captures-only > "$CLUSTER/test-instance-only"
mkdir "$CLUSTER/socket"
"$PG_BIN/initdb" -U page_capture_test -D "$CLUSTER/data" --auth=trust --no-locale --encoding=UTF8 > "$CLUSTER/initdb.log"
trap '"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -m fast -w stop > "$CLUSTER/stop.log" 2>&1 || true; printf "测试实例已停止，保留于 %s\n" "$CLUSTER"' EXIT
"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -l "$CLUSTER/postgres.log" -o "-h '' -k $CLUSTER/socket -p 5432" -w start
"$PG_BIN/createdb" -U page_capture_test -h "$CLUSTER/socket" -p 5432 page_captures_test
cd "$ROOT"
PAGE_CAPTURES_TEST_CLUSTER="$CLUSTER" ./server/node_modules/.bin/vitest run server/src/api/pageCaptures.postgres.test.ts "$@"
