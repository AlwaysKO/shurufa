#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PG_BIN="${PG_BINDIR:-$(dirname "$(command -v initdb)")}"
CLUSTER="$(mktemp -d /tmp/shurufa-chat-retention.XXXXXXXX)"
printf retention-test-only > "$CLUSTER/test-instance-only"
mkdir "$CLUSTER/socket"
"$PG_BIN/initdb" -U ko -D "$CLUSTER/data" --auth=trust --no-locale --encoding=UTF8 > "$CLUSTER/initdb.log"
trap '"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -m fast -w stop > "$CLUSTER/stop.log" 2>&1 || true' EXIT
"$PG_BIN/pg_ctl" -D "$CLUSTER/data" -l "$CLUSTER/postgres.log" -o "-h '' -k $CLUSTER/socket -p 5432" -w start
"$PG_BIN/createdb" -U ko -h "$CLUSTER/socket" -p 5432 chat_retention_test
cd "$ROOT"
CHAT_RETENTION_TEST_CLUSTER="$CLUSTER" ./server/node_modules/.bin/vitest run server/src/api/chatRetention.postgres.test.ts "$@"
