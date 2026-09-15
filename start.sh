#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/env.sh
source "$REPO_ROOT/scripts/lib/env.sh"

environment="local"
dry_run=false

if [[ $# -gt 0 && "$1" != --* ]]; then
  environment="$1"
  shift
fi
case "$environment" in
  local|production) ;;
  *)
    echo "未知环境: $environment（仅支持 local 或 production）" >&2
    exit 2
    ;;
esac

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) dry_run=true ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

env_file="${SHURUFA_ENV_FILE:-$REPO_ROOT/.env.$environment}"
load_dotenv "$env_file"

echo "环境: $environment"
echo "配置: $env_file"
echo "API: ${API_BASE_URL:-未设置}"
echo "管理后台: ${MANAGE_BASE_URL:-未设置}"

commands_local=(
  "npm --prefix server run migrate"
  "npm --prefix server run dev"
  "npm --prefix client run dev -- --host ${DASHBOARD_HOST:-0.0.0.0}"
)
commands_production=(
  "npm --prefix server run migrate"
  "npm --prefix server run build"
  "npm --prefix client run build"
  "npm --prefix server run start"
)

if $dry_run; then
  if [[ "$environment" == local ]]; then
    printf '[dry-run] %s\n' "${commands_local[@]}"
  else
    printf '[dry-run] %s\n' "${commands_production[@]}"
  fi
  exit 0
fi

ensure_dependencies() {
  local component="$1"
  if [[ ! -d "$REPO_ROOT/$component/node_modules" ]]; then
    echo "[$component] 安装依赖..."
    npm --prefix "$REPO_ROOT/$component" ci
  fi
}

ensure_dependencies server
ensure_dependencies client

cd "$REPO_ROOT"

if [[ "$environment" == production ]]; then
  if [[ "${PGPASSWORD:-}" == "请替换为线上数据库强密码" || -z "${PGPASSWORD:-}" ]]; then
    echo "请先在 .env.production 配置真实 PGPASSWORD" >&2
    exit 1
  fi
  npm --prefix server run migrate
  npm --prefix server run build
  npm --prefix client run build
  echo "前端产物: $REPO_ROOT/client/dist（由 mymanage.dog8ball.com 的 Nginx 托管）"
  exec npm --prefix server run start
fi

npm --prefix server run migrate

if adb_bin="$(find_adb)" && "$adb_bin" get-state >/dev/null 2>&1; then
  reverse_target_port="${PORT:-3000}"
  if [[ "$adb_bin" == *.exe ]]; then
    reverse_target_port="${ADB_RELAY_PORT:-3001}"
    wsl_host="$(hostname -I | awk '{print $1}')"
    relay_script="$(wslpath -w "$REPO_ROOT/scripts/windows/start-wsl-relay.ps1")"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$relay_script" \
      -ListenPort "$reverse_target_port" -TargetHost "$wsl_host" -TargetPort "${PORT:-3000}"
  fi
  "$adb_bin" reverse "tcp:${PORT:-3000}" "tcp:$reverse_target_port"
  echo "ADB reverse 已建立：手机 127.0.0.1:${PORT:-3000} → API ${PORT:-3000}"
else
  echo "警告：WSL ADB 未识别到手机，本地 Debug 包暂时无法通过 USB 上报。" >&2
  echo "连接修复后可手动执行: adb reverse tcp:${PORT:-3000} tcp:${PORT:-3000}" >&2
fi

server_pid=""
client_pid=""
cleanup() {
  trap - EXIT INT TERM
  [[ -n "$server_pid" ]] && kill "$server_pid" 2>/dev/null || true
  [[ -n "$client_pid" ]] && kill "$client_pid" 2>/dev/null || true
  wait "$server_pid" "$client_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

npm --prefix server run dev &
server_pid=$!
npm --prefix client run dev -- --host "${DASHBOARD_HOST:-0.0.0.0}" &
client_pid=$!

echo "本地服务启动中：API http://127.0.0.1:${PORT:-3000}，后台 http://127.0.0.1:5175"
echo "另开终端查看上报：./scripts/watch-reporting.sh"

set +e
wait -n "$server_pid" "$client_pid"
status=$?
set -e
echo "有一个开发进程已退出，正在停止其余进程。" >&2
exit "$status"
