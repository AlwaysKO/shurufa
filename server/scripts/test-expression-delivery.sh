#!/usr/bin/env bash
# 只启动独立 PostgreSQL 实例；不读取应用或生产数据库连接。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PG_BIN="${PG_BINDIR:-/usr/lib/postgresql/14/bin}"
CLUSTER="$(mktemp -d /tmp/shurufa-delivery.XXXXXXXX)"
printf expression-delivery-only > "$CLUSTER/test-instance-only"
mkdir "$CLUSTER/socket"
"$PG_BIN/initdb" -D "$CLUSTER/data" --auth=trust --no-locale --encoding=UTF8 > "$CLUSTER/initdb.log"
trap '"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -m fast -w stop > "$CLUSTER/stop.log" 2>&1 || true; printf "测试实例已停止，保留于 %s\n" "$CLUSTER"' EXIT
"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -l "$CLUSTER/postgres.log" -o "-h '' -k $CLUSTER/socket -p 5432" -w start
"$PG_BIN/createdb" -h "$CLUSTER/socket" -p 5432 expression_delivery_test
cd "$ROOT"
EXPRESSION_DELIVERY_TEST_CLUSTER="$CLUSTER" ./server/node_modules/.bin/vitest run server/src/api/expressionDelivery.postgres.test.ts "$@"
