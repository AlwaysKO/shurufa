# 候选字号与斗图面板紧凑化、GIF 发送诊断计划

> **For Claude：** 使用 superpowers:subagent-driven-development 依次实现、规格审查、质量审查；继续 main，不创建 worktree。

**目标：** 按用户两张截图先改一版可调布局，并单独定位预览 GIF、微信发送静态的环节。

**架构：** 复用既有字号/布局计算，仅调整候选行和斗图面板的有效尺寸参数；不改键盘按键、不重绘素材、不做播放器或发送大重构。用户已明确要求先改一版再提供自行调试方法。

**技术栈：** Kotlin、XML、Robolectric、Gradle、ADB。

## UI 设计与备选

推荐在既有 Kotlin 尺寸计算和 XML 处最小调整，并给参数索引；仅改 XML 会被运行时覆盖，集中到新通用样式引擎则过度。本轮候选汉字约增加 20%、上方拼音约增加 30%，不扩大键帽文字。右侧操作按钮从两格 44dp 收窄（初值每格 32dp，保持可点），图标同步缩小；标签单行，不以截断 Emoji合成掩盖宽度问题。工具行初值约 30–32dp，减少素材下方多余高度及气泡纵向内缩；极小视口仍保留入口且不遮挡键盘。

## 任务 1：TDD 实现 UI 和调试指引

文件：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetrics.kt`、`ExpressionPanel.kt`、`AiDoutuBadgeDrawable.kt`、`res/layout/sdk_expression_panel.xml`；候选字号实际入口 `view/CandidatesBar.kt`、`adapter/CandidatesBarAdapter.kt` 或既有 typography 常量，避免改共享字号影响剪贴板/符号。

1. 先写失败测试覆盖字号增大、默认369dp/520dpi标签单行宽度、操作区域收窄、工具区域缩小、极小视口与 Emoji 两行选择不回归。
2. 运行对应 Gradle 测试确认预期失败，再实现最小常量/XML/动态布局修改；不为测试抽象大模块。
3. 运行 candidate typography、expression layout/panel/viewport相关测试。更新旧尺寸断言为新规格但保留可访问与不遮挡门禁。
4. 编写 `docs/guides/expression-ui-tuning.md`：按用户截图列参数/文件/单位/增减效果、动态覆盖规则、Windows Android Studio与WSL构建安装方法、首次构建后如何反复修改并重启输入法；原生View不承诺浏览器CSS热刷新。
5. 自审并提交，规格和质量审查后由主代理完整测试及构建APK；不擅自操作用户聊天群发送测试。

## 任务 2：GIF 发送根因调查

1. 先确认用户路径：推荐直接发送还是保存相册后再选图。
2. 检查 ExpressionContentSender/InputView/FileProvider 的原始GIF、MIME和fallback数据流；现有缓存是GIF不能证明微信最终发送动态。
3. 只在明确根因、可复现证据及失败测试后改发送代码；涉及微信能力/限制的事实如需要引用必须核实一手文档，不猜测或承诺改扩展名就能修。
4. 未确认时明确报告调查结果与待验证点，不将发送问题宣布修复，不拖住本轮UI先行交付。

## 验证

- `source /home/ko/android-tools/env.sh` 后，在 Android 工程运行对应 `:yuyansdk:testOfflineDebugUnitTest --tests ...`，最终完整套件与 `:app:assembleOfflineDebug`。
- 备份并恢复 local.properties；如操作真机，结束恢复搜狗默认输入法与 `svc power stayon false`。
- 用 `git status`、`git diff --check` 和实际日志记录测试/构建状态，保留全部已有提交。

## 进度
- [x] 基线 f7dddf8，main 干净，已查看截图并定位动态尺寸入口。
- [x] UI 红绿测试、实现、双审查、构建与用户调试指引（a160b06、bfef9c1）。
- [ ] GIF 发送路径确认与证据定位。

## 本轮验证结果

- 完整 Android 测试 401/401，0 失败/错误/跳过；`:app:assembleOfflineDebug` 成功，见 `artifacts/expression-ui/verification.json` 和 `test-build.txt`。
- 规格复审和代码质量审查通过。质量审查保留一个非阻断测试建议：窄屏按钮坐标可转换为面板坐标再检查边界。
- SDK 配置已逐字节恢复；未操作手机，尚未安装本轮 APK 或做真机视觉验收。
- 已通过 VS Code 命令打开调参源码与 `docs/guides/expression-ui-tuning.md`。
- GIF 静态发送尚未定位/修复；目前检查显示预制资源按原文件及 image/gif 提交，仍需确认用户实际发送路径与接收端结果，不能据此保证微信动态发送。
