# 输入法夜间模式隔离修复计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 系统夜间模式默认不改变输入法选定皮肤，升级安装同样生效。

**架构：** 在现有偏好初始化之前一次性关闭旧的自动跟随设置，保留选定皮肤和后续手动设置。输入法窗口单独禁用 Force Dark，不影响宿主应用。

**技术栈：** Kotlin、Android Views、SharedPreferences、Robolectric。

### 任务 1：偏好迁移回归测试与实现
- 新建 `android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/prefs/KeyboardNightModeMigrationTest.kt`，覆盖新安装、旧安装皮肤保留、重复启动不重置手动设置。
- 先运行 `:yuyansdk:testOfflineDebugUnitTest --tests '*KeyboardNightModeMigrationTest'` 确认失败。
- 新建 `prefs/KeyboardNightModeMigration.kt`，使用一次性标记，将 `follow_system_dark_mode` 设为 false；未保存普通皮肤时沿用白天皮肤。
- 在 `application/Launcher.kt` 的 AppPrefs 初始化前调用迁移；`data/theme/ThemePrefs.kt` 默认值改为 false。

### 任务 2：防止系统二次着色
- 在 `res/values/themes.xml` 定义独立 `Theme.ImeTheme`（系统 InputMethod 主题，`android:forceDarkAllowed=false`）。
- 在 `service/ImeService.kt` 的 `super.onCreate()` 前设置主题，并在 Android 10+ 禁止窗口根视图 Force Dark。
- 新建 `tools/test_keyboard_night_mode.py` 对 XML 与入口连接作结构回归，先红后绿。

### 任务 3：验证
- 运行新增测试、现有皮肤和迁移测试，构建 debug APK，执行 `git diff --check`。
- 真机验收：选定皮肤后，在键盘打开、隐藏再显示、重启进程三种情况下开关系统深色模式，键盘颜色应不变；手动切换皮肤仍正常。
- 不提交其他已有改动，不自动安装 APK 或改变设备设置。

## 执行结果
- 修复前：4 项结构测试失败；迁移测试 4 项中 3 项按预期失败。
- 修复后：工具目录 10 项 Python 测试通过；迁移、既有 T9 迁移与皮肤共 11 项 JVM 测试通过。
- `:app:assembleOfflineDebug` 构建成功（现有 AGP 8.7.2 / compileSdk 36 兼容提示仍存在）。
- 只读代码审查未发现阻断问题；`git diff --check` 通过。
- 未连接真机，视觉验收待完成；未自动安装或提交。构建期间临时使用的 Linux SDK 路径已恢复为原 Windows 配置。
