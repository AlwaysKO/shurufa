# 固定原签名与 APK 交付

## 现行规则（2026-09-18 用户最终决定）

取消新密钥迁移，始终使用原先能覆盖安装的证书。`E:\Projects\shurufa-android\miaoyan.jks`
现在是原 `C:\Users\ES-11013\.android\debug.keystore` 的字节相同副本，不是先前新建的key0密钥。
被替换的新密钥已经备份到本机 signing-backup/new-key-not-in-use-日期时间，不应再选它打包。

固定证书 SHA256：`a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。
别名为 `androiddebugkey`。密码只存在本机 `keystore/keystore.properties` 等私有配置，不能提交Git。
当前不使用lineage、不使用签名轮换、不卸载/清除手机数据。

## Android Studio 打包

### Mac

1. Android Studio 打开 `/Users/pj/project/shurufa/android/YuyanIme`，等待 Gradle 同步完成。
2. `View > Tool Windows > Build Variants` 中将 `app` 选为 `offlineDebug`，与当前手机的 debug 包保持一致。
3. `Build > Generate Bundle(s) / APK(s) > Generate APK(s)`。菜单也可能显示为 `Generate App Bundles or APKs`；旧版名称是 `Build Bundle(s) / APK(s) > Build APK(s)`。
4. 等待 Build 成功，点击通知中的 `locate` 查找构建文件。本项目执行 `assembleOfflineDebug` 后会用原签名验证并自动复制到项目根 `apk/`，命名包含日期、版本和短 SHA256。原始输出在 `android/YuyanIme/app/build/unix/outputs/apk/offline/debug/`。

无需新建签名密钥或使用签名向导。绿色 Run 用于连接手机调试，产物可能带 `testOnly`；分享安装包使用上述 Generate APKs 或下列 assemble 命令。

在 Android Studio 的 Terminal 中也可执行（使用项目本机 JDK17 与 SDK 环境）：

```bash
cd /Users/pj/project/shurufa
source .runtime/macos/android-env.sh
./android/YuyanIme/gradlew -p android/YuyanIme :app:assembleOfflineDebug --offline -Pandroid.injected.testOnly=false
```

若 IDE 的 Gradle JDK 配置错误，在 `Android Studio > Settings > Build, Execution, Deployment > Build Tools > Gradle` 选择项目使用的 JDK17。本机 SDK 路径 `/Users/pj/Library/Android/sdk`。

官方菜单说明：https://developer.android.com/build/build-for-release 。

### Windows / WSL

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

### 安装并检查后台设置

需要电脑代安装并自动处理电池优化时，使用下列入口（先连接并授权设备）：

```bash
python3 android/YuyanIme/tools/install_with_setup.py /完整路径/交付包.apk \
  --adb /Users/pj/Library/Android/sdk/platform-tools/adb \
  --build-tools /Users/pj/Library/Android/sdk/build-tools/35.0.0
```

从项目根目录运行；按本机实际 SDK 版本调整 `--build-tools`，多设备连接时加 `--serial 设备序列号`。Windows 使用相应 Python/adb.exe/SDK 路径。需要可运行的 Java，签名检查沿用本项目交付校验。

入口先验证原签名、非 testOnly 和项目包名，使用 `adb install -r` 覆盖安装，不主动强停、卸载、清数据或降级。只有安装成功后才添加目标应用的电池优化豁免并回读；厂商拒绝配置时明确报告安装已成功而配置未完成。输出中的无障碍启用不等于服务正在连接。

普通 Android Studio 绿色 Run **不会**执行这个脚本；直接 Run 或手机安装 APK 时，由应用设置页在隐私确认和输入法配置后检查剩余项目。设置齐全时更新后不弹；缺项每次安装最多自动提示一次，跳过后可在「其他 → 后台运行设置」继续。电池豁免和无障碍读取系统实际状态；厂商启动管理无通用读取接口，需一次手动确认并保留。不会静默授予无障碍、改变采集开关或反复拉起系统页面。

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
