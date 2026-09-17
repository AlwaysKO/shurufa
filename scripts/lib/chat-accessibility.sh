#!/usr/bin/env bash

ensure_chat_accessibility() {
  local adb_bin="$1"
  local package_name="${CHAT_ACCESSIBILITY_PACKAGE:-com.yuyan.pinyin.offline.debug}"
  local component="$package_name/com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService"
  local enabled

  [[ "${AUTO_ENABLE_CHAT_ACCESSIBILITY:-false}" == "true" ]] || return 0
  "$adb_bin" shell pm path "$package_name" >/dev/null 2>&1 || return 0
  enabled="$("$adb_bin" shell settings get secure enabled_accessibility_services 2>/dev/null || true)"
  if [[ ":$enabled:" != *":$component:"* ]]; then
    enabled="${enabled/#null/}"
    enabled="${enabled#:}"
    if [[ -n "$enabled" ]]; then
      enabled="$enabled:$component"
    else
      enabled="$component"
    fi
    "$adb_bin" shell settings put secure enabled_accessibility_services "$enabled"
  fi
  "$adb_bin" shell settings put secure accessibility_enabled 1
}
