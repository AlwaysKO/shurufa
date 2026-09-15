# 九宫格长词过滤与候选按钮遮挡修复计划

**目标：** 按用户确认，纯首字母长词完全不显示，保留全拼与混拼；候选右侧采用渐隐加不透明按钮底板。

**证据：** 实际打包词库中 `649439` 唯一匹配为 `美国最高法院 / mei guo zui gao fa yuan`，六个音节全部简拼；OfflineT9Candidates 对六位以上编码将本地结果置于原生之前。搜狗 20.14.0 的定向反编译 `IMEInputCandidateViewContainer.R()` 使用独立 BUTTON_FADDING，部分主题使用 LEFT_RIGHT 透明到主题色渐变，按钮底板独立绘制。原始第三方代码只留在 /tmp，不入库。

## 实现
1. TDD：真实词库、混拼、全拼、原生索引和学习历史回归。长词界定为四字及以上；纯数字输入下，汉字数不少于按键数的长词不进入拼写候选。英文、三字短简拼及上屏后联想不变。
2. T9Lexicon 在截取前过滤；OfflineT9Candidates 在排序前过滤原生与学习结果，避免历史重新注入。
3. CandidateSelection 记录后续原生页的过滤后索引映射；RimeEngine 过滤分页并跳过完全被过滤的页面。
4. TDD：CandidateOverlaySpecTest 验证渐隐、全高遮挡、末尾滚动空间与颜色更新；CandidatesBar 在按钮左侧绘制独立渐隐层，底板保持不透明，按钮覆盖全行。
5. 运行相关 JVM 测试、构建 Debug APK、git diff --check，做独立代码审查。无设备则明确真机未验证。不修改其他任务文件，不自动提交。

## 验证结果
- 新增核心回归测试修复前 5 项按预期失败，覆盖真实词库/学习、分页索引和遮挡。
- 独立审查指出首屏全过滤后后续合法候选不可达；已补首屏预取及映射/入口测试，复核通过。
- 最终相关 JVM 测试 38 项通过，工具目录 Python 测试 12 项通过。
- `:app:assembleOfflineDebug` 成功，`git diff --check` 通过；保留既有 AGP 与 compileSdk 兼容警告。
- 无 ADB 设备，真机候选和渐隐视觉仍待验收。未安装、未自动提交，Windows SDK 本地配置已恢复。
