# macOS 本地开发

项目目录：`/Users/pj/project/shurufa`。

## 后台

- 管理后台：http://127.0.0.1:5175
- 开发登录：`admin` / `adminhaha`
- API 健康检查：http://127.0.0.1:3000/health
- 数据库：本机 PostgreSQL 14，数据库 `personal_ime`，角色 `ime`。随机数据库密码只存于 Git 忽略的 `.env.local`。
- 本地数据库为新库，未导入线上数据。

在终端启动（也可以 Finder 中按 Command+Shift+G 输入下列脚本完整路径打开）：

```bash
/Users/pj/project/shurufa/.runtime/macos/start.command
```

停止本次启动的 API 和后台（保留数据库运行）：

```bash
/Users/pj/project/shurufa/.runtime/macos/stop.command
```

本机脚本使用当前安装的 Node.js 22.19.0；若升级或移除该版本，需要更新脚本中的 Node 路径。
日志：`.runtime/macos/api.log`、`dashboard.log`、`postgres.log`。这些本机配置和脚本不提交 Git；项目原 `start.sh` 的 `wait -n` 需要新版 Bash，不能用 macOS 自带 Bash 3.2 直接运行。

## Android

Android Studio 已安装到 `/Applications/Android Studio.app`（Quail 4 Patch 1）。其内置 Java 25 用于 IDE 自身；本项目 Gradle 8.9 通过 `.gradle/config.properties` 和 `.idea/gradle.xml` 指定 Amazon Corretto 17.0.20.1，安装于 `~/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk`。Java 21 会让未显式配置目标的 Kotlin 任务默认生成 21 字节码，与项目 Java 17 不一致，因此本机改用 JDK 17。

Android Studio 打开目录：`/Users/pj/project/shurufa/android/YuyanIme`。
SDK 目录：`/Users/pj/Library/Android/sdk`，已写入 Git 忽略的 `local.properties`。

终端载入本机 Android 环境：

```bash
cd /Users/pj/project/shurufa
source .runtime/macos/android-env.sh
```

签名使用项目根目录 `/Users/pj/project/shurufa/miaoyan.jks`，私有配置保存在 `android/YuyanIme/keystore/keystore.properties`；均被 Git 忽略。已核对原证书 SHA256：`a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。密钥与密码不要提交 Git。

本机调试包的 API 地址为 `http://127.0.0.1:3000`，由 `~/.gradle/init.d/shurufa-local.gradle` 仅对当前工程 Debug 构建配置，Release 和其他工程不受影响。后台本机启动入口 `.runtime/macos/dev-server.ts` 关闭移动端线上地址发现，其他请求使用原 API 实现；因此模拟器测试数据留在本机。不要把该本地联调 APK 当作线上发布包。

设备连接后通过 `adb -s <设备序列号> reverse tcp:3000 tcp:3000` 建立本机上报通道。

Mac 打包入口：

```bash
/Users/pj/project/shurufa/.runtime/macos/build-apk.command
```

该脚本执行 `packageOfflineDebug`，保留原证书签名校验，检查 API23/27/28/32/36 证书与非 testOnly 属性，然后复制到本机 `apk/`。它不执行 Windows 专属的 assemble 分享复制步骤。本机无法访问固定 Windows `E:\Projects\shurufa-android\apk`，未完成该目录交付；Android Studio 普通 assemble 的原 E 盘路径仍然存在，在这台 Mac 打可分享包请用上述脚本。

当前已安装并验证的 APK：

`/Users/pj/project/shurufa/apk/shurufa-2026-09-22-v20260922.19-2026092219-debug-b69f7e43.apk`

版本 `20260922.19` / `2026092219`，包名 `com.yuyan.pinyin.offline.debug`。
SHA256：`b69f7e43ecd1d502aa3df59b5b764ff3b370ad6c6b69cb24592dbcc3f08b6133`。

模拟器准备完成后的启动入口：

```bash
/Users/pj/project/shurufa/.runtime/macos/start-android.command
```

它使用 `shurufa_api_36` ARM64 虚拟设备，并为模拟器建立本机 API 反向转发。首次系统启动需要稍等。

## 2026-09-21 至 2026-09-22 验证记录

- 2026-09-21 首次执行 001–022；2026-09-22 仓库更新后执行到 026，迁移全部成功。
- 2026-09-22 当前代码的服务端和前端重新构建成功；前端有现有的大文件体积提示。后台已重启，并改为 tsx watch 自动重载。
- API 健康检查、后台首页、登录、用户目录、指定 user_id 的统计接口验证成功。
- 登录专项测试：9/9 通过。
- 初次全量服务端测试：615 通过，19 失败，95 跳过。失败包括 macOS `/var` 与 `/private/var` 路径比较差异、素材母图缺失、渲染 5 秒超时，以及 `deviceIsolation.test.ts` 只加载旧迁移导致缺少 `merged_into_id`。实际数据库已执行 022 迁移。
- 2026-09-22：Android Studio 已安装、完整应用签名验证通过且进程成功启动；API 36、Build Tools 34.0.0、Platform Tools 37.0.1、Emulator 37.1.11、Gradle 8.9 已安装并核对版本。
- 首次启动向导需要在 Android Studio 窗口完成；本机未授予终端 UI 辅助访问权限，无法代点界面；已向 IDE 发送打开项目请求，日志确认开始加载 YuyanIme。SDK 路径选择 `/Users/pj/Library/Android/sdk`。
- Android 16 ARM64 模拟器 `shurufa_api_36` 已创建并启动，设备 `emulator-5554`，`sys.boot_completed=1`。通过 adb reverse 从模拟器请求本机 `/health`，实际返回 HTTP 200 和 `{"status":"ok"}`。
- 2026-09-22 安卓代码编译检查通过：`:app:compileOfflineDebugKotlin`，JDK 17，31 个任务，`BUILD SUCCESSFUL`。随后 `packageOfflineDebug` 打包成功；原证书与非 testOnly 检查通过，已安装到模拟器。
- 模拟器中已启用妙言输入法，九宫格 `64426` 显示 `nihao` 和首候选“你好”，点击后系统搜索框实际显示“你好”；本地数据库收到 `commit | 你好`，设备目录收到模拟器自动注册。证据截图：`.runtime/macos/keyboard-verified.png`。
- 本轮只验证安装、启动、基本输入和本地联通，未验收真机聊天采集、各聊天 App 发送和所有业务功能。

JDK 下载与 SHA-256 来源：[Amazon Corretto 官方下载清单](https://docs.aws.amazon.com/corretto/latest/corretto-17-ug/downloads-list.html)。
