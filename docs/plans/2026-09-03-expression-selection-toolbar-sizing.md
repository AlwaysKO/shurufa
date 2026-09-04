# 斗图选择清空与工具栏尺寸实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 选中任意斗图候选后立即清空当前输入，并调整 AI 斗图胶囊与顶部工具栏图标的视觉尺寸。

**架构：** 清空动作绑定在“选中候选”时刻，不依赖后续发送结果，因此取消或失败也不会恢复文字。视觉尺寸继续由现有 `KeyboardToolbarMetrics` 和 `AiDoutuBadgeDrawable` 统一计算，不改变工具栏排序或面板高度。

**技术栈：** Kotlin、Android View、Robolectric、JUnit 4。

---

### 任务 1：选中候选即清空输入

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionSelectionInputClearer.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionSelectionInputClearerTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/ExpressionManualSearchInputViewTest.kt`

1. 先写失败测试，要求清空组合态、删除宿主输入，且候选点击同步触发清空。
2. 运行定向测试，确认因缺少行为而失败。
3. 在启动发送协程之前清空组合文字、宿主光标前文字和本地查询状态。
4. 重跑定向测试。

### 任务 2：对齐工具栏视觉比例

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/KeyboardToolbarModel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/view/CandidatesBar.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/AiDoutuBadgeDrawable.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/KeyboardToolbarModelTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`

1. 先写失败测试：普通图标画布增大，收起箭头使用与普通图标相当的方形画布；AI 胶囊视觉高度小于工具行且文字为 `12sp`。
2. 运行测试确认失败。
3. 最小调整尺寸常量、箭头画布和胶囊绘制内边距。
4. 运行定向及完整 Android 单测，构建 Offline Debug APK。
