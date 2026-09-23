#!/usr/bin/env bash
# 只启动专用测试数据库，不加载 .env 或业务数据库配置。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PG_BIN="${PG_BINDIR:-/opt/homebrew/opt/postgresql@14/bin}"
CLUSTER="$(mktemp -d /tmp/shurufa-chat-recovery.XXXXXXXX)"
printf chat-recovery-only > "$CLUSTER/test-instance-only"
mkdir "$CLUSTER/socket"
"$PG_BIN/initdb" -D "$CLUSTER/data" --auth=trust --no-locale --encoding=UTF8 > "$CLUSTER/initdb.log"
trap '"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -m fast -w stop > "$CLUSTER/stop.log" 2>&1 || true; printf "测试实例已停止，保留于 %s\n" "$CLUSTER"' EXIT
"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -l "$CLUSTER/postgres.log" -o "-h '' -k $CLUSTER/socket -p 5432" -w start
"$PG_BIN/createdb" -h "$CLUSTER/socket" -p 5432 chat_recovery_test
cd "$ROOT"
CHAT_RECOVERY_TEST_CLUSTER="$CLUSTER" ./server/node_modules/.bin/vitest run server/src/chat/pendingScreenshotRecovery.postgres.test.ts "$@"
