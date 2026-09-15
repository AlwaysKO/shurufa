#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FAKE_DIR="$(mktemp -d)"

cat > "$FAKE_DIR/adb" <<'ADB'
#!/usr/bin/env bash
case "${1:-}" in
  get-state) echo device ;;
  devices) printf 'List of devices attached\nphone-001\tdevice product:test model:TestPhone\n' ;;
  reverse)
    if [[ "${2:-}" == "--list" ]]; then
      echo 'phone-001 tcp:3000 tcp:3000'
    fi
    ;;
  logcat) echo '08-24 16:30:00 I/ShurufaCollector: 事件批量上报成功 count=1 code=200' ;;
esac
ADB
chmod +x "$FAKE_DIR/adb"

cat > "$FAKE_DIR/curl" <<'CURL'
#!/usr/bin/env bash
url="${*: -1}"
case "$url" in
  */health) echo '{"status":"ok"}' ;;
  */devices) echo '{"devices":[{"id":"device-1","brand":"Xiaomi","model":"TestPhone","last_seen_at":"2026-08-24T08:30:00Z"}]}' ;;
  */events*) echo '{"total":1,"items":[{"event_type":"commit","text":"测试上报","package_name":"com.test","occurred_at":"2026-08-24T08:30:00Z"}]}' ;;
  *) exit 22 ;;
esac
CURL
chmod +x "$FAKE_DIR/curl"

output="$(
  PATH="$FAKE_DIR:$PATH" \
  ADB="$FAKE_DIR/adb" \
  SHURUFA_ENV_FILE="$REPO_ROOT/.env.local.example" \
  bash "$REPO_ROOT/scripts/report-status.sh" local
)"
[[ "$output" == *"后端健康: 正常"* ]]
[[ "$output" == *"phone-001"* ]]
[[ "$output" == *"TestPhone"* ]]
[[ "$output" == *"测试上报"* ]]

output="$(ADB="$FAKE_DIR/adb" bash "$REPO_ROOT/scripts/watch-reporting.sh")"
[[ "$output" == *"事件批量上报成功"* ]]

echo "reporting-scripts-test: PASS"
