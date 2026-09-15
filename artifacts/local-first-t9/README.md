# 本地优先九宫格测试包

- APK：`shurufa-local-first-t9-20260907.apk`
- 包名：`com.yuyan.pinyin.offline.debug`
- 版本：`20260907.22`
- SHA-256：`456dadddf0eef5002d4cef6e3bd9c63be011f990c08366e20c1ef4ab09a31cf0`

```bash
source ~/android-tools/env.sh
adb install -r artifacts/local-first-t9/shurufa-local-first-t9-20260907.apk
adb reverse tcp:3000 tcp:3000
adb logcat -s ShurufaCollector OfflineT9
```

安装后在系统中选择此 Debug 输入法。断网输入 `46898262`、`468982624` 检查“候选词”；上屏选词记录在手机保存，联网后分别补传本地与 `https://my.dog8ball.com`，等待至少 30 秒。电脑看板 `http://localhost:5175/` 要选中同一手机用户。

验证：SDK 全量 458 项测试通过，APK 构建与签名、词库打包校验通过；未连接真机，实际手感/两端入库尚待手机验证。

范围和旧云联想协议限制见 `docs/plans/2026-09-07-local-first-t9.md`、`docs/android-integration.md`。
