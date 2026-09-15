#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
START="$REPO_ROOT/start.sh"

fail() {
  echo "测试失败: $*" >&2
  exit 1
}

assert_contains() {
  local output="$1"
  local expected="$2"
  [[ "$output" == *"$expected"* ]] || fail "输出中缺少：$expected"
}

run_dry() {
  SHURUFA_ENV_FILE="$REPO_ROOT/.env.local.example" bash "$START" "$@" --dry-run 2>&1
}

output="$(run_dry)"
assert_contains "$output" "环境: local"
assert_contains "$output" "npm --prefix server run migrate"
assert_contains "$output" "npm --prefix server run dev"
assert_contains "$output" "npm --prefix client run dev"
[[ "$output" != *"--mode local"* ]] || fail "Vite 禁止使用保留的 local mode"

output="$(SHURUFA_ENV_FILE="$REPO_ROOT/.env.production.example" bash "$START" production --dry-run 2>&1)"
assert_contains "$output" "环境: production"
assert_contains "$output" "npm --prefix server run build"
assert_contains "$output" "npm --prefix client run build"
assert_contains "$output" "npm --prefix server run start"

if SHURUFA_ENV_FILE="$REPO_ROOT/.env.local.example" bash "$START" staging --dry-run >/dev/null 2>&1; then
  fail "未知环境 staging 应被拒绝"
fi

missing="$REPO_ROOT/.env.does-not-exist"
if SHURUFA_ENV_FILE="$missing" bash "$START" local --dry-run >/dev/null 2>&1; then
  fail "缺少环境文件时应失败"
fi

echo "start-script-test: PASS"
