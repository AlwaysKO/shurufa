# 固定原签名与 APK 交付

## 现行规则（2026-09-18 用户最终决定）

取消新密钥迁移，始终使用原先能覆盖安装的证书。`E:\Projects\shurufa-android\miaoyan.jks`
现在是原 `C:\Users\ES-11013\.android\debug.keystore` 的字节相同副本，不是先前新建的key0密钥。
被替换的新密钥已经备份到本机 signing-backup/new-key-not-in-use-日期时间，不应再选它打包。

固定证书 SHA256：`a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。
别名为 `androiddebugkey`。密码只存在本机 `keystore/keystore.properties` 等私有配置，不能提交Git。
当前不使用lineage、不使用签名轮换、不卸载/清除手机数据。

## Android Studio 打包

正常使用 Build APKs / Generate APKs，或在 YuyanIme 根目录的 Terminal 执行：

```powershell
.\gradlew.bat :app:assembleOfflineDebug
# 或
.\gradlew.bat :app:assembleOfflineRelease
```

`tools/signing-delivery.gradle` 会在 APK 打包后统一原签名并验证API23/28/36证书，
再复制到 `E:\Projects\shurufa-android\apk\shurufa-日期-v版本名-版本码-debug或release-短SHA.apk`。
IDE Run也统一原签名，但testOnly包不复制到分享目录。
如果使用签名向导，选择上面的 miaoyan.jks 和 androiddebugkey，不要再选原新密钥别名key0。
本流程只处理APK，不是AAB/Google Play发布流程。

## 兼容与数据边界

- 相同包名、相同签名且版本不降级，才能正常覆盖更新；签名统一不保证任何设备无条件安装。
- 当前APK要求Android 6.0+和ARM64系统。系统管理/安装策略仍可能限制安装。
- debug/release包名不同，release不能覆盖debug或自动迁移其数据/权限。
- 本次生成的新签名迁移包已移入apk/archive-not-for-install/new-key-rotation，不能用于日常安装。
- 若其他设备实际安装过新证书包，应先核对其证书，不能承诺此原证书包可覆盖，也不能擅自卸载。

## 密钥与验证

不要删除原密钥；安全备份 miaoyan.jks 和本机密码/别名。私有配置已Git忽略。
缺少密钥配置或证书不匹配时会停止构建/交付，不能改成临时生成另一把密钥。

```powershell
python tools/verify-delivery-apk.py <APK> <SDK/build-tools/版本>
```

该检查验证API23/27/28/32/36均为原证书且不是testOnly；不替代手机功能验收。
