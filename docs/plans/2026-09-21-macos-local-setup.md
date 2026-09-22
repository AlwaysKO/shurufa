# macOS 本地运行 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在当前 Apple Silicon Mac 安装 Android Studio，并启动 shurufa 数据库、管理后台和安卓开发环境。

**Architecture:** 沿用仓库 Node.js/Vue/PostgreSQL 及 Android Gradle 工程。使用本机私有配置和独立数据库；签名遵循 tools/SIGNING.md，缺失原密钥时等待用户提供，不替换证书。遵循用户要求，始终在当前分支操作。

**Tech Stack:** Android Studio、Android SDK 36、Gradle 8.9、Java 17/21、Node.js 22、PostgreSQL 14、Vue/Vite。

---

### Task 1: 安装 Android 工具链
- 安装 Android Studio 和 command-line tools，SDK 放在 ~/Library/Android/sdk。
- 创建 android/YuyanIme/local.properties（Git 忽略）。
- 验证 Studio 应用存在、Java/SDK/ADB 版本和 ARM64 模拟器启动。

### Task 2: 启动后台
- 安装 server/client lockfile 依赖。
- 使用本机 PostgreSQL，新建项目角色和 personal_ime 数据库；配置 .env.local（Git 忽略）。
- 执行原有 migrate，启动 API 3000 和管理后台 5175。
- 验证数据库查询、HTTP 健康检查、后台登录和前端构建。

### Task 3: 安卓连接与构建
- 查验原证书及私有签名配置是否可用。原密钥缺失时仅推进独立的环境准备。
- 配置 Gradle JDK 与 SDK，构建离线 Debug，核验证书及 APK 元数据。
- 在用户选定的模拟器或设备安装启动，通过 adb reverse 连接本机 API，检查运行日志。
- 当前 Mac 无法访问固定 Windows E 盘交付目录时明确记录，不伪造该目录。

### Task 4: 运行交接
- 在 .runtime/macos 保存本机启动/停止脚本与日志，文档记录访问地址、启动方法和未完成依赖。
- 最终重新检查进程、接口、Git diff 和安卓实际状态。

## 2026-09-22 执行结果

- Android Studio、JDK 17、SDK 36、Gradle 8.9 已安装并验证；ARM64 模拟器已启动。
- 数据库迁移到 026，前后端构建通过，后台登录/API/模拟器到本机 API 联通验证通过。
- Android Java/Kotlin 编译通过；缺少原签名密钥与密码，未打包 APK、未安装输入法，也未复制 Windows E 盘交付目录。
- 仅新增运行说明/计划，配置、脚本、日志与工具下载留在 Git 忽略的本机路径；未切换分支、未创建 worktree、未提交或推送。

## 2026-09-22 原密钥到位后的闭环

- 根目录 miaoyan.jks 的证书与要求一致，私有签名配置已补齐。
- 本机 Debug 构建固定本地 API，开发服务关闭移动端线上地址发现，避免联调跳转线上。
- 非 testOnly APK 已打包、原证书校验通过、复制到 Mac apk/ 并安装到 ARM64 模拟器；Windows E 盘目录不可达，未同步。
- 九宫格输入 nihao → 候选你好 → 上屏你好已实际验证；本机后台收到设备与 commit 输入记录。
- 最终使用方法和唯一当前 APK 见 docs/MACOS_LOCAL_SETUP.md。
