# AI 斗图聊天场景与搜狗式布局实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 所有输入框保留独立“AI斗图”工具行，仅在聊天编辑器中显示搜狗式标签行和推荐图片，并按参考截图比例适配常规 Android 手机。

**架构：** 用纯 Kotlin `ChatEditorGate` 组合包名白名单和 `EditorInfo` 特征，输入目标切换时把结果写入 `ExpressionPanelState` 并取消不再有效的异步任务。`ExpressionPanel` 拆成条件推荐区和常驻工具行，尺寸通过独立的比例计算器从可用宽度换算并设置上下限。

**技术栈：** Kotlin、Android View/XML、RecyclerView、Robolectric、JUnit 4、Gradle Offline Debug。

---

### 任务 1：新增聊天编辑器门控

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ChatEditorGate.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ChatEditorGateTest.kt`

**步骤 1：编写失败测试**

覆盖以下行为：

```kotlin
assertTrue(gate.allows("com.tencent.mm", multilineEditor()))
assertTrue(gate.allows("com.whatsapp", sendEditor()))
assertFalse(gate.allows("com.tencent.mm", searchEditor()))
assertFalse(gate.allows("com.android.settings", multilineEditor()))
assertFalse(gate.allows("com.tencent.mm", passwordEditor()))
```

白名单精确包含：

```text
com.tencent.mm
com.tencent.mobileqq
com.hihonor.mms
com.ss.android.lark
org.telegram.messenger
com.whatsapp
com.discord
```

**步骤 2：运行测试验证失败**

```bash
cd /home/ko/project/shurufa/android/YuyanIme
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ChatEditorGateTest
```

预期：FAIL，`ChatEditorGate` 尚不存在。

**步骤 3：编写最少实现**

实现纯函数，先验证包名，再排除 `TYPE_TEXT_VARIATION_PASSWORD`、`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`、`TYPE_TEXT_VARIATION_WEB_PASSWORD`、邮箱、网址和 `IME_ACTION_SEARCH`，最后接受 `TYPE_TEXT_FLAG_MULTI_LINE` 或 `IME_ACTION_SEND`。不读取包管理器，不引入可配置后台。

**步骤 4：复测并提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ChatEditorGate.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ChatEditorGateTest.kt
git commit -m "feat: 限制斗图推荐到聊天编辑器"
```

### 任务 2：把场景门控接入推荐状态和输入目标生命周期

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionPanelState.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionPanelStateTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`

**步骤 1：编写失败测试**

为状态增加聊天编辑器开关，断言：

```kotlin
state.setChatEditor(false)
state.beginQuery("你好", 1)
assertFalse(state.applyResults(1, listOf(asset("hello"))))
assertFalse(state.isRecommendationVisible)
assertTrue(state.isToolRowVisible)

state.setChatEditor(true)
state.beginQuery("你好", 2)
assertTrue(state.applyResults(2, listOf(asset("hello"))))
assertTrue(state.isRecommendationVisible)
```

再断言从聊天编辑器切换到非聊天编辑器会清空查询、结果、展开态并递增请求版本，但不改变持久化 AI 开关。

**步骤 2：运行测试验证失败**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ExpressionPanelStateTest
```

预期：FAIL，状态尚无聊天门控。

**步骤 3：编写最少实现**

- `ExpressionPanelState` 增加 `chatEditor`、`isRecommendationVisible` 和始终为真的 `isToolRowVisible`；
- 非聊天态拒绝 `beginQuery/applyResults/expand`，切换输入目标时清空瞬态状态；
- `InputView.onStartInputView()` 使用 `ChatEditorGate` 计算当前目标；
- `resetExpressionTarget(editorInfo)` 取消搜索、预览、下载、准备任务并初始化新状态；
- `searchExpressions()` 再做一次聊天门控，避免旧回调触发查询；
- 工具行在非聊天软件中只控制持久化开关，不发起推荐。

**步骤 4：复测并提交**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ChatEditorGateTest \
  --tests com.yuyan.imemodule.expression.ExpressionPanelStateTest \
  --tests com.yuyan.imemodule.expression.ExpressionQueryCoordinatorTest
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionPanelState.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionPanelStateTest.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt
git commit -m "feat: 按输入目标控制斗图推荐"
```

### 任务 3：拆分常驻工具行和条件推荐区

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/bg_expression_action_capsule.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/bg_expression_tool_badge.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/ic_ai_sticker_close_target.xml`

**步骤 1：编写失败测试**

Robolectric 断言：

- `expression_recommendation_section` 只在 `state.isRecommendationVisible` 时显示；
- `expression_tool_row` 在聊天、非聊天、开启、关闭状态均显示；
- 推荐区在 XML 中位于工具行之前；
- 标签行右侧存在单一胶囊容器，内部依次为 `…` 和圆环关闭按钮；
- 原 `×` drawable 不再被面板引用；
- 关闭按钮隐藏推荐区，点击工具标签重新开启；
- 标签行、图片区、工具行、候选词和键盘的现有相对顺序不被破坏。

**步骤 2：运行测试验证失败**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionPanelTest
```

预期：FAIL，推荐区和工具行当前共用同一行。

**步骤 3：编写最少实现**

- XML 使用纵向容器包裹 `expression_recommendation_section`，内部为标签行和图片区；
- 在推荐区之后增加常驻 `expression_tool_row`，右侧放白色圆角、蓝紫色“AI斗图”标签；
- 标签行右侧使用白色圆角胶囊，保留现有设置菜单回调，关闭按钮换为圆环实心点 vector；
- `ExpressionPanel.render()` 只切换推荐区可见性，不再隐藏工具行；
- 点击工具标签只在关闭态调用 `onAiStickerEnabledChange(true)`。

**步骤 4：复测并提交**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionPanelTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionGestureTest
git add android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml \
  android/YuyanIme/yuyansdk/src/main/res/drawable/bg_expression_action_capsule.xml \
  android/YuyanIme/yuyansdk/src/main/res/drawable/bg_expression_tool_badge.xml \
  android/YuyanIme/yuyansdk/src/main/res/drawable/ic_ai_sticker_close_target.xml \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt
git commit -m "feat: 拆分斗图推荐区和工具行"
```

### 任务 4：按参考图比例实现自适应像素规格

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetrics.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetricsTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionAssetAdapter.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_expression_asset.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/drawable/selector_expression_tab_background.xml`

**步骤 1：编写失败测试**

以参考图 `443px` 宽度为比例基准，断言 HONOR 200 输入区参数：

```kotlin
val metrics = ExpressionLayoutMetrics.calculate(widthPx = 1200, density = 3.25f, landscape = false)
assertEquals(approximately(79.dp), metrics.itemSizePx)
assertEquals(approximately(7.dp), metrics.itemGapPx)
assertEquals(approximately(17.dp), metrics.horizontalPaddingPx)
assertTrue(metrics.visibleItemCount in 4.1f..4.4f)
```

同时断言小屏、普通 1080p、平板和横屏均落在尺寸上下限，卡片保持正方形，标签行约 `36dp`、图片区约 `96dp`、工具行约 `34dp`、胶囊约 `79×28dp`。

**步骤 2：运行测试验证失败**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionLayoutMetricsTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionPanelTest
```

预期：FAIL，当前卡片固定为 `104dp`，且没有比例计算器。

**步骤 3：编写最少实现**

- 以参考宽度比例计算卡片、间距和边距，再转为像素并应用 dp 上下限；
- 竖屏卡片目标比例 `93/443`，间距 `8/443`，边距 `21/443`；
- 横屏保持比例但限制卡片和行高最大值；
- Adapter 绑定时使用计算后的正方形尺寸；
- 图片列表应用左右 padding 和相邻间距；
- 标签选中线、文字颜色、浅灰背景、卡片 padding 和圆角按参考图调整；
- 不写死 1200px，不读取具体手机型号。

**步骤 4：复测并提交**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionLayoutMetricsTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionPanelTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionGestureTest
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetrics.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetricsTest.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionAssetAdapter.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt \
  android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_expression_asset.xml \
  android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml \
  android/YuyanIme/yuyansdk/src/main/res/drawable/selector_expression_tab_background.xml
git commit -m "feat: 按搜狗比例适配斗图布局"
```

### 任务 5：全量验证、代码审查和真机像素校准

**文件：**
- 仅在验证发现本计划范围内问题时修改对应文件和测试。

**步骤 1：Android 全量验证和 APK**

```bash
cd /home/ko/project/shurufa/android/YuyanIme
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug
```

预期：全部测试通过并生成 `app/build/outputs/apk/offline/debug/*.apk`。

**步骤 2：代码审查**

使用 `superpowers:requesting-code-review`，重点检查：

- 非聊天编辑器不会查询或显示推荐；
- 工具行始终独立存在；
- 标签行和图片区只随聊天推荐出现；
- 关闭图标不再是 `×`；
- 轻点、长按、直接发送和旧请求隔离没有回归；
- 未混入服务端、采集、位置或 Dashboard 等无关修改。

关键/重要问题继续按 TDD 独立提交。

**步骤 3：安装和安全真机校准**

复用已有 Windows Platform Tools：

```text
/home/ko/android-tools/win_sdk/platform-tools_r37.0.1-win.zip
C:\Users\Public\shurufa-platform-tools-r37.0.1\platform-tools\adb.exe
```

安装最新 APK 后：

1. 在系统设置搜索框确认只有独立“AI斗图”工具行，不出现标签和图片；
2. 在无收件人的系统短信新建草稿输入 `ni`，确认未选词时无图片；
3. 选择“你好”，确认出现标签行和至少 4 张完整文字图片；
4. 对照参考截图测量行高、卡片大小、间距、边距、胶囊尺寸和可见卡片数量；
5. 如需调整，先修改对应 metrics 测试期望并观察 RED，再改常量、复测并独立提交；
6. 验证圆环关闭、独立工具行重开、长按三列、返回恢复和无确认弹层；
7. 不设置收件人，不点击短信“发送”；退出并放弃草稿。

**步骤 4：最终仓库和设备恢复**

```bash
cd /home/ko/project/shurufa
git diff --cached --name-status
git diff --check 5a1342c..HEAD
git status -sb
```

恢复输入法为 `com.sohu.inputmethod.sogou/.SogouIME`，恢复 `stay_on_while_plugged_in=0`。使用 `superpowers:verification-before-completion` 与 `superpowers:finishing-a-development-branch` 汇报 APK、真机验收、未推送提交和剩余次要问题，由用户决定是否推送。
