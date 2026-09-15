#!/usr/bin/env bash
set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/lib/env.sh
source "$REPO_ROOT/scripts/lib/env.sh"

environment="${1:-local}"
case "$environment" in
  local|production) ;;
  *) echo "未知环境: $environment" >&2; exit 2 ;;
esac

env_file="${SHURUFA_ENV_FILE:-$REPO_ROOT/.env.$environment}"
load_dotenv "$env_file" || exit 1
api_base="${API_BASE_URL:-http://127.0.0.1:${PORT:-3000}}"
api_base="${api_base%/}"
issues=0

echo "=== Shurufa 上报状态（$environment） ==="
echo "API: $api_base"

if health="$(curl -fsS --max-time 3 "$api_base/health" 2>/dev/null)"; then
  echo "后端健康: 正常 $health"
else
  echo "后端健康: 异常（无法访问 $api_base/health）"
  issues=1
fi

echo
echo "--- ADB 手机 ---"
if adb_bin="$(find_adb)"; then
  adb_output="$($adb_bin devices -l 2>&1)"
  echo "$adb_output"
  if [[ "$(printf '%s\n' "$adb_output" | awk '$2 == "device" { count++ } END { print count+0 }')" -eq 0 ]]; then
    echo "ADB 状态: 未识别到已授权手机"
    issues=1
  else
    echo "ADB 状态: 正常"
    reverse_output="$($adb_bin reverse --list 2>/dev/null || true)"
    if [[ "$reverse_output" == *"tcp:${PORT:-3000} tcp:"* ]]; then
      echo "端口反向代理: 正常（tcp:${PORT:-3000}）"
    else
      echo "端口反向代理: 缺失，请运行 adb reverse tcp:${PORT:-3000} tcp:${PORT:-3000}"
      issues=1
    fi
  fi
else
  echo "ADB 状态: 未找到 adb 命令"
  issues=1
fi

if [[ "$health" == *'"status"'* ]]; then
  echo
  echo "--- 最近设备 ---"
  if devices_json="$(curl -fsS --max-time 5 "$api_base/api/v1/dashboard/devices" 2>/dev/null)"; then
    printf '%s' "$devices_json" | node -e '
      let raw=""; process.stdin.on("data", c => raw += c).on("end", () => {
        const rows = JSON.parse(raw).devices ?? [];
        if (!rows.length) return console.log("暂无设备注册记录");
        rows.slice(0, 5).forEach(d => console.log(`${d.brand ?? ""} ${d.model ?? d.name ?? d.id} | ${d.id} | last_seen=${d.last_seen_at}`.trim()));
      });
    '
  else
    echo "设备查询失败"
    issues=1
  fi

  echo
  echo "--- 最近 10 条事件 ---"
  if events_json="$(curl -fsS --max-time 5 "$api_base/api/v1/dashboard/events?days=1&page_size=10&all=1" 2>/dev/null)"; then
    if ! printf '%s' "$events_json" | node -e '
      let raw=""; process.stdin.on("data", c => raw += c).on("end", () => {
        const data = JSON.parse(raw); const rows = data.items ?? [];
        if (!rows.length) {
          console.log("暂无最近一天的上报事件");
          process.exitCode = 3;
          return;
        }
        rows.forEach(e => console.log(`${e.occurred_at} | ${e.event_type} | ${e.package_name ?? "-"} | ${(e.text ?? "").slice(0, 80)}`));
      });
    '; then
      issues=1
    fi
  else
    echo "事件查询失败"
    issues=1
  fi
fi

echo
if [[ $issues -eq 0 ]]; then
  echo "结论: 上报链路当前正常。"
else
  echo "结论: 上报链路尚未完全就绪，请按上面的异常项处理。"
fi
exit "$issues"
