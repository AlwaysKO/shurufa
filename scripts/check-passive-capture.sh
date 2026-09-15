#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$REPO_ROOT/android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule"
CAPTURE_DATA="$ROOT/data/capture"
CAPTURE_SERVICE="$ROOT/service/capture"

for directory in "$CAPTURE_DATA" "$CAPTURE_SERVICE"; do
  if [[ ! -d "$directory" ]]; then
    echo "被动采集目录不存在: $directory" >&2
    exit 1
  fi
done

if rg -n 'performAction\(|dispatchGesture\(|performGlobalAction\(' "$CAPTURE_DATA" "$CAPTURE_SERVICE"; then
  echo "发现禁止的主动 UI 操作 API" >&2
  exit 1
fi
