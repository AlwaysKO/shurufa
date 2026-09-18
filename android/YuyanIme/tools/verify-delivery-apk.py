#!/usr/bin/env python3
"""验证本项目可交付APK在各平台统一使用原签名；不读取任何私钥。"""
import os
from pathlib import Path
import subprocess
import sys

OLD = "a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287"
NEW = OLD

def verify(apk, tools):
    for api, expected in [(23, OLD), (27, OLD), (28, NEW), (32, NEW), (36, NEW)]:
        result = subprocess.run(
            ["java", "-jar", str(tools / "lib/apksigner.jar"), "verify",
             "--min-sdk-version", str(api), "--max-sdk-version", str(api),
             "--print-certs", str(apk)], capture_output=True, text=True, check=True)
        actual = [line.rsplit(": ", 1)[-1].strip() for line in result.stdout.splitlines()
                  if "certificate SHA-256 digest:" in line]
        assert actual == [expected], f"API {api} 签名不符合原签名: {actual}"
        print(f"API {api} 签名验证通过")
    aapt = tools / ("aapt.exe" if os.name == "nt" else "aapt")
    manifest = subprocess.check_output([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
    assert "android:testOnly" not in manifest, "Run测试包不能作为分享交付包"
    print("非 testOnly；原签名验证通过")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("用法: python verify-delivery-apk.py APK SDK/build-tools/版本")
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
