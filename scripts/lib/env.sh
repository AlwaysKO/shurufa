#!/usr/bin/env bash

# 安全加载简单 dotenv 文件：不使用 source/eval，避免空格和特殊字符被 Shell 执行。
load_dotenv() {
  local env_file="$1"
  local line key value

  if [[ ! -f "$env_file" ]]; then
    echo "环境文件不存在: $env_file" >&2
    return 1
  fi

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ "$line" != *=* ]]; then
      echo "环境文件格式错误（缺少 =）: $env_file" >&2
      return 1
    fi

    key="${line%%=*}"
    value="${line#*=}"
    key="${key#${key%%[![:space:]]*}}"
    key="${key%${key##*[![:space:]]}}"
    if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      echo "环境变量名不合法: $key" >&2
      return 1
    fi

    if [[ ${#value} -ge 2 ]]; then
      if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]] ||
         [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
        value="${value:1:${#value}-2}"
      fi
    fi
    export "$key=$value"
  done < "$env_file"
}

find_adb() {
  local candidate
  if [[ -n "${ADB:-}" && -x "$ADB" ]]; then
    printf '%s\n' "$ADB"
    return 0
  fi
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi
  for candidate in \
    "$HOME/android-tools/sdk/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb" \
    /mnt/c/Users/*/Android/platform-tools/adb.exe; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}
