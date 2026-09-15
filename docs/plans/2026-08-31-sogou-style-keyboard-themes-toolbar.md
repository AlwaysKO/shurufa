# 搜狗风格键盘主题与工具栏实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 用项目自有代码和矢量素材实现与参考界面一致的快捷输入方式页、四套主题布局、七槽工具栏和 AI 斗图提示，并在点击主题后立即应用到真实键盘。

**架构：** 复用搜狗 APK 已验证的分层思路：一个共享键盘渲染器读取主题规格、键位覆盖和工具栏规格。主题选择页与预览均由数据驱动，不为四个主题复制 View 树；默认/蓝色共享几何，微信和华为只覆盖必要的底行及视觉参数。

**技术栈：** Kotlin、Android View、自定义 Drawable/View、RecyclerView、Robolectric、JUnit4。

---

### 任务 1：建立四套主题规格

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/KeyboardSurfaceTheme.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/theme/ThemePreset.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/theme/ThemeManager.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/QuickKeyboardSettingsModel.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/KeyboardSurfaceThemeTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/QuickKeyboardSettingsModelTest.kt`

**步骤：**
1. 先写测试，断言主题顺序、标题、颜色、共享/覆盖几何以及四个真实主题 ID。
2. 运行定向测试，确认因规格不存在而失败。
3. 添加 `SogouDefault`、`SogouBlue`、`WechatLayout`、`SogouHuawei` 四个内置主题和纯 Kotlin 主题规格。
4. 运行定向测试确认通过。

### 任务 2：重做快捷输入方式与主题选择页

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/view/KeyboardThemePreviewView.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/view/QuickKeyboardPanelMetrics.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/container/SettingsContainer.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/values/strings.xml`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/QuickKeyboardSettingsViewTest.kt`

**步骤：**
1. 先写 Robolectric 测试，断言顶部导航、输入方式网格、双列主题卡、字号/边距/圆角、单选状态和立即切换。
2. 运行测试确认旧的文字按钮界面失败。
3. 实现数据驱动的输入方式网格、主题双列卡和项目自绘键盘预览。
4. 点击主题后持久化、更新真实键盘并保持面板选中态。
5. 运行测试确认通过。

### 任务 3：重做七槽工具栏与自有矢量按钮

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/KeyboardToolbarModel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/SkbFunData.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/adapter/CandidatesMenuAdapter.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/view/CandidatesBar.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_recyclerview_candidates_menu.xml`
- 修改：相关 `android/YuyanIme/yuyansdk/src/main/res/drawable/ic_menu_*.xml`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/KeyboardToolbarModelTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/adapter/CandidatesMenuAdapterTest.kt`

**步骤：**
1. 先写测试，断言固定顺序为品牌、表情、快捷键盘、剪贴板、光标编辑、AI 斗图、收起。
2. 运行测试确认现有 Voice/手写优先规则失败。
3. 将五个中间槽改为等宽，图标视觉画布按参考 78/1080 比例缩放，点击区域仍覆盖整个槽。
4. 用项目自有矢量路径统一线宽、圆角端点和视觉中心；品牌入口保留项目标识。
5. 运行定向测试确认通过。

### 任务 4：AI 斗图气泡与交互

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/AiDoutuBadgeDrawable.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`

**步骤：**
1. 先写测试，断言 AI 斗图气泡的高度、内边距、文字样式、锚点和点击行为。
2. 运行测试确认失败。
3. 实现圆角气泡和底部指向角，并继续复用现有“无文字时提示”搜索流程。
4. 运行测试确认通过。

### 任务 5：真实键盘主题覆盖

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/TextKeyboard.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouT9Layout.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouQwertyLayout.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/KeyboardSurfaceThemeTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouT9LayoutTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouQwertyLayoutTest.kt`

**步骤：**
1. 先写测试覆盖默认/蓝色共享几何、微信底行五键、华为底行顺序与主题色。
2. 运行测试确认缺少覆盖能力。
3. 在生成 `SoftKeyboard` 时读取主题规格，只覆盖不同键位和宽度，不复制整个键盘构造过程。
4. 主题切换时清理键盘缓存并立即重建。
5. 运行定向测试确认通过。

### 任务 6：完整验证

**文件：**
- 检查：本计划涉及的全部文件

**步骤：**
1. 运行所有新增和受影响的单元测试。
2. 运行 `:yuyansdk:testDebugUnitTest`。
3. 运行 `:app:assembleDebug` 或项目中对应的可安装 Debug 构建任务。
4. 检查 `git diff --check` 和 `git status --short`，保留此前 KSP 修复，不覆盖无关改动。

