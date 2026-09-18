#!/usr/bin/env bash
# 永不读取业务数据库连接；新建实例、专用数据库及 socket，结束只停止自己的实例。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PG_BIN="${PG_BINDIR:-/usr/lib/postgresql/14/bin}"
CLUSTER="$(mktemp -d /tmp/shurufa-timeline-beijing.XXXXXXXX)"
printf timeline-beijing-only > "$CLUSTER/test-instance-only"
mkdir "$CLUSTER/socket"
"$PG_BIN/initdb" -D "$CLUSTER/data" --auth=trust --no-locale --encoding=UTF8 > "$CLUSTER/initdb.log"
trap '"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -m fast -w stop > "$CLUSTER/stop.log" 2>&1 || true; printf "测试实例已停止，保留于 %s\n" "$CLUSTER"' EXIT
"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -l "$CLUSTER/postgres.log" -o "-h '' -k $CLUSTER/socket -p 5432" -w start
"$PG_BIN/createdb" -h "$CLUSTER/socket" -p 5432 timeline_beijing_test
cd "$ROOT"
TIMELINE_BEIJING_TEST_CLUSTER="$CLUSTER" ./server/node_modules/.bin/vitest run server/src/api/timeline.postgres.test.ts "$@"
