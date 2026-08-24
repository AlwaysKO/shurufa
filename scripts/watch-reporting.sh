#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/lib/env.sh
source "$REPO_ROOT/scripts/lib/env.sh"

if ! adb_bin="$(find_adb)"; then
  echo "未找到 adb。请安装 Android platform-tools 或设置 ADB=/path/to/adb。" >&2
  exit 1
fi
if ! "$adb_bin" get-state >/dev/null 2>&1; then
  echo "ADB 未识别到已授权手机；先运行 '$adb_bin devices -l' 检查连接。" >&2
  exit 1
fi

echo "正在查看 ShurufaCollector 实时日志，按 Ctrl+C 退出。"
echo "正常上报会显示：设备注册上报成功 / 事件批量上报成功 / 位置上报成功。"
exec "$adb_bin" logcat -v time 'ShurufaCollector:I' '*:S'
