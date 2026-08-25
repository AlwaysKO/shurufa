# 搜狗式中文九宫格布局实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 将妙言默认中文九宫格调整为参考截图中的搜狗键位和尺寸，并实现中央键短按空格、长按语音。

**架构：** 用独立的 `SogouT9Layout` 保存可测试的布局规格，`KeyboardLoaderUtil` 仅负责把规格转换为现有 `SoftKey`。通过 `SoftKey.longPressAction` 表达语音长按语义，由 `BaseKeyboardView` 复用现有长按计时器转发 `InputView.startVoiceInput()`；偏好迁移在 `Launcher` 初始化 `AppPrefs` 前执行一次。

**技术栈：** Kotlin、Android IME View、SharedPreferences、JUnit 4、Robolectric、Gradle。

---

### 任务 1：固化并验证搜狗九宫格几何与键位规格

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouT9Layout.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouT9LayoutTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt:100-140,315-430`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/entity/keyboard/SoftKeyboard.kt:12-18`

**步骤 1：编写失败测试**

测试起点 `0.005f`、左右列 `0.17f`、中间列 `0.21666667f`、五列终点 `0.995f`；右侧第三键为 `KEYCODE_0`；底栏为符号、数字、空格、语言、回车，宽度总和为 `0.99f`。

```kotlin
@Test fun mainColumnsFillKeyboard() {
    assertEquals(0.995f, SogouT9Layout.columnRightEdges.last(), 0.0001f)
}

@Test fun rightAndBottomKeysMatchSogou() {
    assertEquals(KeyEvent.KEYCODE_0, SogouT9Layout.rightColumn.last())
    assertArrayEquals(
        intArrayOf(USER_KEYCODE_SYMBOL, USER_KEYCODE_NUMBER, KeyEvent.KEYCODE_SPACE, USER_KEYCODE_LANG, KeyEvent.KEYCODE_ENTER),
        SogouT9Layout.bottomRowCodes,
    )
}
```

**步骤 2：运行测试验证失败**

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*SogouT9LayoutTest'
```

预期：FAIL，提示 `SogouT9Layout` 不存在。

**步骤 3：实现最小规格与加载逻辑**

```kotlin
object SogouT9Layout {
    const val KEYBOARD_HEIGHT_RATIO = 0.278f
    const val CANDIDATE_TEXT_SIZE_PERCENT = 45
    const val START_X = 0.005f
    const val SIDE_WIDTH = 0.17f
    const val MAIN_WIDTH = 0.21666667f
    const val X_MARGIN_SCALE = 0.7f
    const val Y_MARGIN_SCALE = 0.6f
    val rightColumn = intArrayOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_0)
    val bottomRowCodes = intArrayOf(USER_KEYCODE_SYMBOL, USER_KEYCODE_NUMBER, KeyEvent.KEYCODE_SPACE, USER_KEYCODE_LANG, KeyEvent.KEYCODE_ENTER)
    val bottomRowWidths = floatArrayOf(0.17f, 0.165f, 0.32f, 0.165f, 0.17f)
}
```

在中文 T9 分支使用新列宽和键码，删除原来的 `@`；中文 T9 单独生成底栏，不进入按主题分叉的通用 `lastRows()`。`SoftKeyboard` 接受可选 margin scale，使中文 T9 的默认视觉间距约为横向 7‰、纵向 12‰，其他布局保持原值。符号键显示文本覆盖为“符”。

**步骤 4：运行测试验证通过**

运行步骤 2 的命令，预期 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouT9Layout.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/entity/keyboard/SoftKeyboard.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouT9LayoutTest.kt
git commit -m "feat: 改为搜狗式中文九宫格键位"
```

### 任务 2：实现语音/空格双功能键

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/entity/keyboard/LongPressAction.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/entity/keyboard/SoftKey.kt:12-30`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/BaseKeyboardView.kt:96-124`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/KeyIconPreset.kt:15-31`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouT9LayoutTest.kt`

**步骤 1：编写失败测试**

```kotlin
@Test fun centerBottomKeyIsSpaceWithVoiceLongPress() {
    val key = SogouT9Layout.createVoiceSpaceKey()
    assertEquals(KeyEvent.KEYCODE_SPACE, key.code)
    assertEquals(LongPressAction.Voice, key.longPressAction)
}
```

**步骤 2：运行测试验证失败**

运行任务 1 的定向测试，预期因 `LongPressAction` 或 `createVoiceSpaceKey` 不存在而 FAIL。

**步骤 3：实现最小交互**

新增 `enum class LongPressAction { Default, Voice }`。`SoftKey` 增加 `longPressAction`，默认 `Default`。语音/空格键保持 `KEYCODE_SPACE`，标记为 `Voice` 并使用现有 `ic_menu_voice` 图标状态。`BaseKeyboardView.openPopupIfRequired()` 优先处理 `Voice`：设置 `mLongPressKey = true`、`mAbortKey = true`，调用 `mService?.startVoiceInput()`，不打开符号弹窗；抬手不再发送空格。短按和左右滑动继续走原有空格逻辑。

**步骤 4：运行测试验证通过**

运行任务 1 定向测试，预期 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/entity/keyboard/LongPressAction.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/entity/keyboard/SoftKey.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/BaseKeyboardView.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/KeyIconPreset.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouT9LayoutTest.kt
git commit -m "feat: 增加长按语音的空格键"
```

### 任务 3：更新默认尺寸并迁移已有安装

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/prefs/SogouT9PreferenceMigration.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/prefs/SogouT9PreferenceMigrationTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/prefs/AppPrefs.kt:25-35,118-130`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/application/Launcher.kt:28-37`

**步骤 1：编写失败测试**

测试新安装默认值为高度 `0.278f`、候选字号 `45`；旧安装执行迁移后得到相同值；第二次执行迁移不重复改变结果。

```kotlin
@Test fun migratesExistingInstallOnce() {
    preferences.edit().putFloat("keyboard_height_ratio", 0.3f).putInt("candidate_size", 55).commit()
    SogouT9PreferenceMigration.migrate(preferences)
    assertEquals(0.278f, preferences.getFloat("keyboard_height_ratio", 0f), 0.0001f)
    assertEquals(45, preferences.getInt("candidate_size", 0))
    assertEquals(1, preferences.getInt("sogou_t9_layout_version", 0))
}
```

**步骤 2：运行测试验证失败**

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*AppPrefsDefaultsTest' --tests '*SogouT9PreferenceMigrationTest'
```

预期：FAIL，旧默认断言不符或迁移对象不存在。

**步骤 3：实现最小迁移**

将 `AppPrefs` 默认键盘高度改为 `SogouT9Layout.KEYBOARD_HEIGHT_RATIO`，候选字号改为 `SogouT9Layout.CANDIDATE_TEXT_SIZE_PERCENT`。迁移对象检查 `sogou_t9_layout_version`，首次运行写入两个值和版本 `1`；`Launcher.currentInit()` 在 `AppPrefs.init()` 前调用迁移。

**步骤 4：运行测试验证通过**

运行步骤 2，预期两个测试类全部 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/prefs/SogouT9PreferenceMigration.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/prefs/AppPrefs.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/application/Launcher.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/prefs/AppPrefsDefaultsTest.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/prefs/SogouT9PreferenceMigrationTest.kt
git commit -m "feat: 迁移搜狗式键盘尺寸默认值"
```

### 任务 4：回归测试、构建和截图复核

**文件：** 仅在发现直接相关问题时修改以上文件。

**步骤 1：运行完整 SDK 单元测试**

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand
```

预期：BUILD SUCCESSFUL，全部测试通过。

**步骤 2：构建完整 APK**

```bash
./gradlew :app:assembleOfflineDebug --configure-on-demand
```

预期：BUILD SUCCESSFUL，并在 `app/build/outputs/apk/offline/debug/` 生成 APK。

**步骤 3：检查工作区边界**

```bash
git status --short
git diff --check
git diff --stat HEAD~3..HEAD
```

预期：无空白错误；不包含用户已有的服务端关系档案修改、`app/src/debug/` 或网络安全测试文件。

**步骤 4：设备截图复核**

安装 APK 后在 923×2048 或同纵横比设备检查：按键区约 558px；主键约 188px 宽；左右列约 143–145px；纵向视觉间距约 12–13px；右下为 `0`；底栏顺序正确；候选字变小；中央键短按空格、长按语音。

**步骤 5：最终提交（仅在验证阶段有修正时）**

```bash
git add <仅本功能修正文件>
git commit -m "fix: 修正搜狗式九宫格布局细节"
```
