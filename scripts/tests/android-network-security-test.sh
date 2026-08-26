#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android/YuyanIme"
DEBUG_MANIFEST="$ANDROID_DIR/app/build/intermediates/merged_manifest/offlineDebug/processOfflineDebugMainManifest/AndroidManifest.xml"
RELEASE_MANIFEST="$ANDROID_DIR/app/build/intermediates/merged_manifest/offlineRelease/processOfflineReleaseMainManifest/AndroidManifest.xml"
DEBUG_CONFIG="$ANDROID_DIR/app/src/debug/res/xml/network_security_config.xml"

if ! command -v java >/dev/null 2>&1 && [[ -x /home/ko/android-tools/jdk-17.0.20+8/bin/java ]]; then
  export JAVA_HOME=/home/ko/android-tools/jdk-17.0.20+8
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ -z "${RELEASE_STORE_FILE:-}" && -f /home/ko/android-tools/debug.keystore ]]; then
  export RELEASE_STORE_FILE=/home/ko/android-tools/debug.keystore
fi

(
  cd "$ANDROID_DIR"
  ./gradlew \
    :app:processOfflineDebugMainManifest \
    :app:processOfflineReleaseMainManifest \
    --offline --console=plain >/dev/null
)

python3 - "$DEBUG_MANIFEST" "$RELEASE_MANIFEST" "$DEBUG_CONFIG" <<'PY'
import sys
import xml.etree.ElementTree as ET

debug_manifest_path, release_manifest_path, debug_config_path = sys.argv[1:]
android = "{http://schemas.android.com/apk/res/android}"

debug_app = ET.parse(debug_manifest_path).getroot().find("application")
assert debug_app is not None
assert debug_app.get(android + "networkSecurityConfig") == "@xml/network_security_config", (
    "Debug 构建必须声明本地网络安全配置"
)

release_app = ET.parse(release_manifest_path).getroot().find("application")
assert release_app is not None
assert release_app.get(android + "usesCleartextTraffic") != "true", (
    "Release 构建不能允许明文 HTTP"
)
assert release_app.get(android + "networkSecurityConfig") is None, (
    "Debug 的本地明文 HTTP 配置不能进入 Release 构建"
)

config = ET.parse(debug_config_path).getroot()
base = config.find("base-config")
assert base is not None and base.get("cleartextTrafficPermitted") == "false", (
    "Debug 默认也必须禁止明文 HTTP"
)

allowed_domains = {
    domain.text
    for domain_config in config.findall("domain-config")
    if domain_config.get("cleartextTrafficPermitted") == "true"
    for domain in domain_config.findall("domain")
}
assert "127.0.0.1" in allowed_domains, "Debug 必须仅显式放行本地 ADB 地址"
PY

echo "Android Debug/Release 网络安全策略测试通过"
