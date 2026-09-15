# 九宫格充电宝与学习召回修复计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 召回充电宝，并让同码反复选中的合法词不因首屏截断而丢失个人排序。

**架构：** 保留 Rime 与本地个人权重模型，不改数据库结构；本地词库匹配校验与展示数量解耦，对历史词进行定向召回。补充自维护词典中的充电宝，末音节支持任意已输入前缀，不能补写内部音节。

**技术栈：** Kotlin、SQLite、JUnit、Robolectric、Gradle wrapper。

## 范围与来源
- 当前用户 2026-09-11：2466434262 应支持充电宝，反复选词后提升到首位。
- docs/android-integration.md：保留密码/禁止个性化学习边界、十四天衰减、原生翻页索引、锁拼音/分段回原生规则。
- 不发布、不安装、不清空旧词库、不修改无关未提交工作。

## 任务 1：先复现
- 修改 `android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/completion/OfflinePersonalCandidatesTest.kt`：实际资产召回、选择三次及重开数据库、超过八条的合法历史词与非法历史隔离。
- 修改同目录 `T9LexiconTest.kt`：末字输入 ba 与 b/bao 一致召回，内部简拼仍被排除。
- 执行 `source ~/android-tools/env.sh && cd android/YuyanIme && ./gradlew :yuyansdk:testDebugUnitTest --configure-on-demand --tests '*OfflinePersonalCandidatesTest' --tests '*T9LexiconTest'`，确认新增断言因缺陷失败。

## 任务 2：最小修复
- `android/YuyanIme/yuyansdk/src/main/assets/completion/chinese_domains.tsv` 补充 `充电宝\tchong dian bao\t100`。
- `.../data/completion/T9Lexicon.kt`：末音节允许长度大于一的前缀；查询可额外保留指定历史词，不放宽读音校验。
- `.../data/completion/OfflineT9Candidates.kt`：查询前读取历史，用合法完整匹配结果召回首屏外历史词，保留 pinyin 和 nativeIndex；不改个人权重公式。
- 重跑上述测试及 completion、T9Spelling、提交、隐私、原生索引相关测试。

## 任务 3：验收
- 检查 diff，运行 SDK 全量单元测试与离线 APK 构建（可用时）。
- 记录已验证与手机未验收边界；文档只追加本任务结果，不覆盖用户改动。不自动提交或发布。

## 执行记录
- RED：`/tmp/shurufa-t9-red.log`，22 项中新增 3 项按预期失败：缺少充电宝、末音节 ba 断召回、首屏外合法历史被过滤。
- GREEN：`/tmp/shurufa-t9-green.log`，同组 22 项通过；随后补充首屏外学习词与原生重复时的选择/翻页索引断言，纳入全量回归。
- 代码审查未发现本轮阻断问题，补充上述索引测试建议已落实。
- 尚未验收手机端。保留原生默认候选优先于末音节补全的旧策略，并未保证未学习时充电宝一定是第一。验证目标是可召回及连续 3 次选择超过未学习的默认项；已有其他高权重个人习惯仍按衰减权重竞争，不强行永久置顶。
- 已知未覆盖：本地词典未收录且原生首屏没有的历史词仍缺少可校验读音；分段/锁拼音沿用原生，本轮未扩展为整串数字的整词学习。不能将当前回归说成所有输入路径均已修复。
- 最终相关回归：`./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand --tests 'com.yuyan.imemodule.data.completion.*' --tests 'com.yuyan.imemodule.data.collect.*' --tests '*T9*Test' --tests '*RimeEngineCompositionStateTest'` 成功；XML 汇总 27 个套件、104 项测试、0 失败/错误/跳过。日志 `/tmp/shurufa-t9-regression.log`。
- 全量回归未通过：运行至表情 `ExpressionManualSearchInputViewTest` 出现 `OutOfMemoryError`；在 441 项、3 个 OOM 失败、1 跳过时主动终止本轮测试工作进程（最终退出 143），没有修改表情代码或全局堆配置。日志 `/tmp/shurufa-t9-suite.log`。不能声称全量通过。
- `git diff --check` 通过；未生成或安装 APK，未提交或发布。现有未提交改动保留。
