#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FAKE="$(mktemp -d)"
trap 'rm -rf "$FAKE"' EXIT

cat > "$FAKE/adb" <<'ADB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$ADB_CALLS"
if [[ "$*" == "shell pm path "* ]]; then
  echo package:/data/app/base.apk
elif [[ "$*" == "shell settings get secure enabled_accessibility_services" ]]; then
  echo null
fi
ADB
chmod +x "$FAKE/adb"
export ADB_CALLS="$FAKE/calls"

# shellcheck source=scripts/lib/chat-accessibility.sh
source "$ROOT/scripts/lib/chat-accessibility.sh"

AUTO_ENABLE_CHAT_ACCESSIBILITY=false ensure_chat_accessibility "$FAKE/adb"
[[ ! -e "$ADB_CALLS" ]]

AUTO_ENABLE_CHAT_ACCESSIBILITY=true ensure_chat_accessibility "$FAKE/adb"
grep -Fq 'shell settings put secure enabled_accessibility_services com.yuyan.pinyin.offline.debug/com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService' "$ADB_CALLS"
grep -Fq 'shell settings put secure accessibility_enabled 1' "$ADB_CALLS"

echo 'chat-accessibility-test: PASS'
