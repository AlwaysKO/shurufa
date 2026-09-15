# AI斗图与拼音共行实现计划

> 使用 superpowers:subagent-driven-development，任务依次实现、规格审查、质量审查。当前 main，不创建 worktree。

**目标：** AI斗图不再独占工具行，与候选拼音同一行右对齐；空状态整行消失。图片和标签之间4dp，更多/关闭背景上下各3dp留白，可自行调参。

**架构：** 复用 CandidatesBar 的拼音行作为共同行；由 InputView 连接推荐状态及既有收起/恢复动作。移除正式布局中重复工具行占高，保留既有发送/播放器/键帽行为，不新建通用布局框架。

**用户状态规则：** 拼音非空时保留拼音行；推荐结果非空时显示右侧按钮（收起推荐后缓存结果仍可恢复）；两者皆无时隐藏整行。无结果时不显示无效按钮。非聊天输入等原有推荐限制仍保留。

**保护用户修改：** 开始时工作区已有 CandidatesBarAdapter.CANDIDATE_TEXT_SCALE=1.1f、CandidatesBar.COMPOSING_TEXT_SCALE=1.5f。不得覆盖或回退，不将用户字号变更混入本次提交。修改同一文件时仅暂存本任务差异。基线用户补丁 /tmp/shurufa-shared-row/user-before.patch。

## 任务1：TDD实现共行和间距、指南

涉及主源码（前缀 android/YuyanIme/yuyansdk/src/main/）：
- java/com/yuyan/imemodule/view/CandidatesBar.kt
- java/com/yuyan/imemodule/keyboard/InputView.kt
- java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt
- java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetrics.kt
- res/layout/sdk_expression_panel.xml
测试对应 CandidatesBarTest、ExpressionPanelTest、ExpressionLayoutMetricsTest、ExpressionManualSearchInputViewTest、ExpressionViewportObstructionTest，必要时状态测试。

1. 先写失败测试：拼音/结果四种组合行和按钮显隐；推荐收起恢复；按钮和拼音同一实际纵向范围、长拼音不重叠；默认无结果无幽灵高度；图片顶部4dp且不裁；按钮背景上下3dp且图标/点击可用。
2. 跑对应测试确认RED。字体/像素宽度使用Robolectric NATIVE，不能使用LEGACY假度量。
3. 最小实现状态联动与布局；旧“始终保留工具入口”断言按用户新需求替换，保留小视口不遮挡键盘、Emoji可用、十轮布局不反馈坍缩、生命周期门禁。
4. 跑GREEN，自审并仅提交自己改动。主代理并行只读核查交付及用户修改保护，不并行运行Gradle。
5. 更新 docs/guides/expression-ui-tuning.md，写真实文件与独立参数、dp方向、动态覆盖，以及用户自调值不应被覆盖。移除已失效工具行调高说明。

## 验证及审查

- 备份 local.properties 到 /tmp/shurufa-shared-row/local.properties.original；source /home/ko/android-tools/env.sh 后运行 Gradle --project-cache-dir /tmp/shurufa-gradle-wsl-production。
- 定向RED/GREEN；规格通过后质量审查；最终 :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug。
- 恢复SDK配置。没有用户真机授权不操作私人聊天，不宣称真机已验收；GIF发送静态问题独立保留，不声称此次修复。
- git diff --check、检查1.1/1.5与原用户补丁保留；给APK与文件行号指南。

## 验证与交付

- 实现提交 `13d8373`，独立规格与质量审查均通过。
- 定向202/202；完整Android406/406，0失败/错误/跳过；`:app:assembleOfflineDebug`成功。
- 实测发现移除空拼音行后48dp箭头越出44dp候选行，已限制为实际行高；未改FloatCandidateBar。
- 验证汇总/APK路径与SHA：`artifacts/expression-ui/shared-row/verification.json`，完整构建日志 `test-build.txt`。
- APK与测试使用用户未提交的字号1.1/1.5；这两行保留未提交，未混入实现提交。SDK配置逐字节恢复。
- 已在VS Code打开间距常量和指南。本轮未操作手机、未安装APK，真实视觉效果待用户验收；GIF发送静态问题不属于此次修复。
- 自调位置：`ExpressionLayoutMetrics.kt:216` 的 `ACTION_VERTICAL_MARGIN_DP=3f`；`:217` 的 `CONTENT_TOP_GAP_DP=4f`。完整步骤见 `docs/guides/expression-ui-tuning.md`。
