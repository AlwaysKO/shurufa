# 搜狗等价体验键盘与 AI 斗图实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 使用项目自有代码、主题和素材，实现搜狗等价或更好的键盘布局、快捷工具栏、输入法内快捷设置与 AI 斗图交互。

**架构：** 将可测量的键盘几何、文字规格、工具栏合并规则和搜索决策提取为纯 Kotlin 规格对象；Android View 仅渲染规格并转发动作。继续使用现有 `KeyboardLoaderUtil`、`CandidatesBar`、`ExpressionPanelState` 和发送流程，避免重写输入引擎与素材系统。

**技术栈：** Kotlin、Android View、RecyclerView、Room、Robolectric、JUnit 4、Kotlin Coroutines、Gradle Offline flavor。

---

### 任务 1：固化 26 键与九宫格的 APK 几何规格

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouQwertyLayout.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouT9Layout.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouQwertyLayoutTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouT9LayoutTest.kt`

**步骤 1：编写失败测试**

把断言改为 APK `1080/port/26.ini` 与 `9.ini` 的真实值，并新增行起点、行高和底行终点断言：

```kotlin
assertEquals(0.0093f, SogouQwertyLayout.ROW_START_X, 0.0001f)
assertArrayEquals(floatArrayOf(0.0093f, 0.2586f, 0.5078f, 0.7570f), SogouQwertyLayout.rowTop, 0.0001f)
assertEquals(0.2212f, SogouQwertyLayout.KEY_HEIGHT, 0.0001f)
assertArrayEquals(floatArrayOf(0.0078f, 0.2570f, 0.5062f, 0.7555f), SogouT9Layout.rowTop, 0.0001f)
assertEquals(0.24922f, SogouT9Layout.MAIN_KEY_HEIGHT, 0.0001f)
assertEquals(0.2368f, SogouT9Layout.BOTTOM_KEY_HEIGHT, 0.0001f)
```

同时断言视觉间隙不进入触摸范围：每行触摸跨度覆盖相邻按键的中线，首尾覆盖 `0f..1f`。

**步骤 2：运行测试验证失败**

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest \
  --tests '*.SogouQwertyLayoutTest' \
  --tests '*.SogouT9LayoutTest'
```

预期：FAIL，旧近似值 `0.24/0.245/0.005` 与新断言不一致或新字段不存在。

**步骤 3：最小实现真实规格**

在两个规格对象中分别保存 `rowTop`、视觉键宽/高、触摸跨度、功能键宽和底行宽。提供：

```kotlin
data class KeyGeometry(val left: Float, val top: Float, val width: Float, val height: Float)

fun rowGeometry(row: Int): List<KeyGeometry>
```

不要把 APK 文件或素材复制进项目；仅记录测量后的数值和来源注释。

**步骤 4：运行测试验证通过**

重复步骤 2，预期两个测试类 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/Sogou*Layout.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/Sogou*LayoutTest.kt
git commit -m "feat: 精确适配搜狗九宫格与26键几何"
```

### 任务 2：把精确几何接入键盘生成与触摸命中

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/entity/keyboard/SoftKeyboard.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouKeyboardHitMapTest.kt`

**步骤 1：编写失败测试**

构造纯几何键盘，验证视觉间隙、行间隙和屏幕边缘都能映射到合理按键：

```kotlin
@Test fun `相邻字母中线没有触摸死区`() {
    val hitMap = SogouQwertyLayout.touchHitMap()
    assertEquals(KeyEvent.KEYCODE_Q, hitMap.keyAt(0.099f, 0.12f)?.code)
    assertEquals(KeyEvent.KEYCODE_W, hitMap.keyAt(0.100f, 0.12f)?.code)
}

@Test fun `九宫格行间触摸归属最近一行`() {
    assertNotNull(SogouT9Layout.touchHitMap().keyAt(0.5f, 0.253f))
}
```

**步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*.SogouKeyboardHitMapTest'
```

预期：FAIL，新命中 API 不存在。

**步骤 3：实现连续命中区**

- `KeyboardLoaderUtil` 使用规格对象设置每个按键视觉几何。
- `SoftKeyboard` 保存按键触摸边界；`mapToKey` 先判断核心边界，再在同一行用按键中线分区。
- 不跨行吸附，不把左侧九宫格候选/符号占位错误映射为字母键。
- 数字行启用时继续按现有比例缩放，不改变其模式判断。

**步骤 4：验证**

运行任务 1、2 的三个测试类，预期 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/{utils/KeyboardLoaderUtil.kt,entity/keyboard/SoftKeyboard.kt} \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouKeyboardHitMapTest.kt
git commit -m "feat: 消除搜狗式键盘触摸死区"
```

### 任务 3：适配其余受支持键盘与文字层级

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouAuxiliaryLayouts.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouAuxiliaryLayoutsTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/KeyPreset.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/entity/keyboard/SoftKey.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/TextKeyboard.kt`

**步骤 1：编写失败测试**

为手写、笔画、数字、文本编辑建立独立行规格，断言各模式不是错误复用统一 `0.25f` 行高；断言九宫格显示 `abc/def/...` 主标签和右上数字次标签，功能键为 `分词/重输/符/123`。

```kotlin
assertEquals("abc", SogouAuxiliaryLayouts.t9Label(KeyEvent.KEYCODE_A).main)
assertEquals("2", SogouAuxiliaryLayouts.t9Label(KeyEvent.KEYCODE_A).minor)
assertNotEquals(SogouAuxiliaryLayouts.stroke.rows, SogouAuxiliaryLayouts.number.rows)
```

**步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*.SogouAuxiliaryLayoutsTest'
```

预期：FAIL，规格对象不存在且现有 T9 标签为大写。

**步骤 3：实现最小规格和渲染属性**

- 根据 APK 的 `bh.ini`、`digit_9.ini`、`handwriting.ini` 等端口配置记录受支持布局比例。
- `SoftKey` 增加可选的主/次文字位置与字号倍率，不将搜狗字体或图片带入项目。
- `TextKeyboard` 使用项目主题绘制普通键、功能键、强调回车键和次标签。
- 中文/英文 26 键共用几何但保留各自标点；乱序 17 键沿用项目规格。
- 符号面板只调整项目现有容器的网格节奏和触摸目标，不复制搜狗符号资源。

**步骤 4：运行布局测试与原有测试**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest \
  --tests '*.Sogou*Layout*Test' \
  --tests '*.ImeServiceKeyEventTest'
```

预期：PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/{keyboard,utils} \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard
git commit -m "feat: 统一适配搜狗式辅助键盘与文字层级"
```

### 任务 4：定义保留现有功能的搜狗式工具栏模型

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/KeyboardToolbarModel.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/KeyboardToolbarModelTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/prefs/behavior/SkbMenuMode.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/SkbFunData.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/ic_menu_ai_sticker_search.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/ic_menu_quick_keyboard.xml`

**步骤 1：编写失败测试**

工具栏模型接收数据库中的现有项目，输出固定入口加保留项目：

```kotlin
val output = KeyboardToolbarModel.merge(existing)
assertEquals(SkbMenuMode.Emojicon, output[0].skbMenuMode)       // 截图第二按钮
assertEquals(SkbMenuMode.QuickKeyboard, output[1].skbMenuMode) // 截图第三按钮
assertEquals(SkbMenuMode.AiDoutu, output[4].skbMenuMode)       // 截图第六按钮
assertTrue(output.containsAll(existing.filterNot(::isDuplicatePinned)))
```

首个项目菜单按钮和末尾收起按钮仍由 `CandidatesBar` 固定绘制，因此模型下标从截图第二按钮开始。

**步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*.KeyboardToolbarModelTest'
```

预期：FAIL，新模型和菜单类型不存在。

**步骤 3：实现合并规则**

- 新增 `QuickKeyboard` 和 `AiDoutu` 菜单动作。
- 固定表情、快捷键盘、AI 斗图入口；中间位置优先保留用户已有的语音/手写能力。
- 去掉固定入口的重复项，其余数据库项目保持原相对顺序并可横向滚动。
- 新增图标必须是项目自绘 VectorDrawable，不提取 APK 图像。

**步骤 4：运行测试验证通过**

重复步骤 2，预期 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/{java,res/drawable} \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/KeyboardToolbarModelTest.kt
git commit -m "feat: 加入搜狗式表情设置与AI斗图入口"
```

### 任务 5：实现输入法内键盘与主题快捷面板

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/QuickKeyboardSettingsModel.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/QuickKeyboardSettingsModelTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/container/SettingsContainer.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SettingsMenuClick.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`

**步骤 1：编写失败测试**

```kotlin
assertEquals(
    listOf(PINYIN_T9, PINYIN_QWERTY, HANDWRITING, STROKE, NUMBER, SYMBOL, TEXT_EDIT, LX17),
    QuickKeyboardSettingsModel.layouts.map { it.id },
)
assertEquals(listOf("MaterialLight", "MaterialDark"), QuickKeyboardSettingsModel.quickThemes.map { it.name })
```

另测选择布局会生成正确的 `layout/schema` 对，选择主题只允许项目 `ThemeManager` 中存在的主题。

**步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*.QuickKeyboardSettingsModelTest'
```

预期：FAIL，快捷模型不存在。

**步骤 3：实现面板**

- `SettingsContainer.showQuickSettingsView()` 显示布局区与浅色/深色主题区，不启动 Activity。
- 布局点击复用 `InputModeSwitcher.switchModeForSetting` 与 `KeyboardManager`。
- 主题点击复用 `ThemeManager.setNormalModeTheme`，立即调用现有主题刷新链并持久化。
- 当前布局和主题显示选中态；系统返回或再次点击键盘按钮回到输入键盘。

**步骤 4：运行测试和 Robolectric 视图测试**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest \
  --tests '*.QuickKeyboardSettingsModelTest' \
  --tests '*.SettingsContainerTest'
```

若不存在 `SettingsContainerTest`，先新增最小 Robolectric 测试验证点击不产生设置 Activity Intent、当前主题立即变化。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard
git commit -m "feat: 增加输入法内键盘主题快捷设置"
```

### 任务 6：实现 AI 斗图手动搜索决策和精确提示

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionManualSearch.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionManualSearchTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SettingsMenuClick.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/values/strings.xml`

**步骤 1：编写失败测试**

```kotlin
assertEquals(MissingText, ExpressionManualSearch.resolve("  ", null))
assertEquals(Query("民营企业"), ExpressionManualSearch.resolve(null, "民营企业"))
assertEquals(Query("新文字"), ExpressionManualSearch.resolve("新文字", "旧文字"))
```

Robolectric 测试验证 `MissingText` 不调用搜索回调，并显示用户确认的项目字符串“请先输入文字，再点击搜索按钮”。

**步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*.ExpressionManualSearchTest'
```

预期：FAIL，新决策器不存在。

**步骤 3：实现搜索动作**

- 优先读取当前组合/刚提交文字，退回面板最后有效查询，不读取整段历史聊天文本。
- 空文字只 Toast，不展开面板、不启用 AI 开关、不创建网络请求。
- 有文字时启用 AI 斗图并立即绕过自动搜索防抖，复用 `searchExpressions`、取消旧查询和防乱序逻辑。
- 表情入口调用现有 `Emojicon`，不引入搜狗表情。

**步骤 4：运行测试验证通过**

重复步骤 2，并运行 `ExpressionQueryCoordinatorTest`，预期全部 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression
git commit -m "feat: 增加AI斗图手动文字搜索"
```

### 任务 7：接入工具栏并完成灵动表达等价交互

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/view/CandidatesBar.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/adapter/CandidatesMenuAdapter.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetrics.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetricsTest.kt`

**步骤 1：编写失败测试**

新增测试验证：

- 候选为空时工具栏包含固定三入口且保留数据库功能。
- 圆形入口触摸目标至少 44dp，图标按主题着色。
- 上滑/长按只在有结果时展开；点击关闭后折叠并保留恢复入口。
- 标签切换保持当前查询；返回键优先收起展开面板。
- 紧凑态标签栏、结果区、工具栏高度符合截图比例且不遮挡键盘。

**步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest \
  --tests '*.ExpressionPanelTest' \
  --tests '*.ExpressionLayoutMetricsTest' \
  --tests '*.CandidatesBarTest'
```

若 `CandidatesBarTest` 不存在，先创建 Robolectric 测试再运行。预期新增断言 FAIL。

**步骤 3：实现视图交互**

- `CandidatesBar` 使用 `KeyboardToolbarModel.merge`，保留左侧菜单和右侧收起按钮。
- 工具按钮使用圆形/无边框按下态、轻震动和 contentDescription。
- `ExpressionPanel` 增加上滑展开手势；保持已有长按、标签、更多、关闭、缓存和动画逻辑。
- “AI斗图”恢复入口使用项目自绘文字/渐变，不使用“搜狗”“灵动表达”品牌素材。
- 颜色从 `ThemeManager.activeTheme` 获取，不硬编码只适合浅色主题的颜色。

**步骤 4：运行测试验证通过**

重复步骤 2，预期 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main android/YuyanIme/yuyansdk/src/test
git commit -m "feat: 完成AI斗图面板与工具栏等价交互"
```

### 任务 8：回归验证、截图核对与收尾

**文件：**
- 按失败结果只修改直接相关文件
- 更新：`docs/plans/2026-08-27-sogou-equivalent-keyboard-expression-design.md`（仅当实现事实与设计存在必要差异）

**步骤 1：运行键盘与 AI 斗图定向测试**

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest \
  --tests '*.keyboard.*' \
  --tests '*.expression.*'
```

预期：BUILD SUCCESSFUL，相关测试全部通过。

**步骤 2：运行完整离线单元测试**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest
```

预期：BUILD SUCCESSFUL，无新增失败。

**步骤 3：运行静态检查和构建**

```bash
./gradlew :yuyansdk:lintOfflineDebug :app:assembleOfflineDebug
```

预期：构建成功；lint 无本次新增的 error。

**步骤 4：视觉与交互核对**

使用现有键盘预览或设备完成以下截图/操作：

1. 九宫格、中文 26 键、英文 26 键、手写、笔画、数字、符号、文本编辑。
2. 空候选工具栏，确认表情、快捷键盘、AI 搜索位置和既有功能仍在。
3. 无文字点击 AI 搜索，确认只提示“请先输入文字，再点击搜索按钮”。
4. 有文字自动推荐和手动搜索、三标签、横滑、长按/上滑展开、返回折叠。
5. 关闭 AI 斗图、恢复入口、浅色/深色主题即时切换。
6. 连续快速输入、删除和切换聊天输入框，确认无旧图串入、无触摸死区。

**步骤 5：最终提交**

```bash
git status --short
git add <仅本任务直接相关文件>
git commit -m "test: 验证搜狗等价输入体验"
```
