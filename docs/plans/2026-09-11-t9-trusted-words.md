# 九宫格可信整词候选实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 逐步执行。

**目标：** 按用户确认，宁可用短词/单字分段输入，也不以未收录且未确认的中文拼接凑候选；9664337 正常召回用得上。
**架构：** 九宫格现有候选入口增加统一可信词谓词：词典收录、同码/合法同读音相关码的明确选择记录，或单字。该谓词同时用于首屏、历史加入与原生翻页，仍先验证拼音和纯简拼长度。取消词频 1000 门槛，可信整词（含合法末字补全）先于只覆盖部分输入的短词/单字，词频仅用于可信词之间排序。
**技术栈：** Kotlin、SQLite 既有只读历史、JUnit/Robolectric。

## 来源与范围
- 当前用户明确接受“未知新名字/短语先分段选择，不自动猜整串”；取代上一轮保留未收录拼接候选与频率门槛的策略。
- 继续保留内部音节禁扩写、四字以上纯简拼限制、原生索引、跨码同读音校验、隐私边界。不清空已有记录，不硬编码乱词黑名单。
- 作用于现有九宫格候选处理入口（3–30 位纯数字）。手动锁拼音、已分段的原生状态、字母输入和词库外个人词无读音时的首次召回不在本轮扩展。
- 补充此前已确认的常用表达不高兴、你这样、续期，避免本地词典缺漏破坏此前有效示例；用得上已有词条，不靠特判其编码修复。

## 任务 1：红色回归
修改 `android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/completion/OfflinePersonalCandidatesTest.kt`。
新增：总额而拼音匹配但无词依据，首屏及后页不显示；用得上低频仍首选；没有可靠整词时保留总额/单字而不凑串；明确选择过的未收录词经拼音校验仍可用；其他三组用户示例仍有效。
按新规则更新旧断言/夹具（夜魔不再作为未收录词保留；空词库拼音校验测试明确提供有效个人词依据），不删除拼写和索引反例。
运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand --tests '*OfflinePersonalCandidatesTest'`，确认失败原因。

## 任务 2：最小实现
- `.../data/completion/T9Lexicon.kt`：增加独立于前八条及编码长度的文字收录查询，供已确认短词前缀与后页使用。
- `.../data/completion/OfflineT9Candidates.kt`：统一词依据校验，取消高频门槛，可信整词优先于仅匹配输入前缀的短词。
- `.../data/completion/PersonalCandidateRanker.kt` 的 CandidateSelection：接受可选额外原生过滤谓词，后页执行同一可信规则；不改原生连续索引或个人权重公式。
- `.../assets/completion/chinese_domains.tsv`：补既有合法样例常用词，不添加错误字符串黑名单。

## 任务 3：验证
运行 completion、collect、T9、RimeEngine 相关回归；只读审查首屏/翻页/历史是否存在绕过；检查 diff，更新 docs/android-integration.md 的现行规则与证据。不安装/发布 APK，不宣称实机或全量测试通过。

## 执行记录
- 首轮 RED：23 项中 5 项按预期失败，覆盖用得上优先、未知整词首屏/后页/空列表、明确个人词依据；日志 `/tmp/shurufa-trusted-red.log`。
- 追加混合字符串反例：未知拼接附加表情仍不得显示。该测试先失败（`/tmp/shurufa-trusted-mixed-red.log`），随后改为逐 Unicode 码点统计汉字数，避免非汉字旁路，并兼容 minSdk 23（不引入 Java Stream API）。
- 原生字母注释测试显式隔离本地词库，避免新增“不高兴”词条使词库读音与原生注释来源混淆；原生注释断言保留，字母合并顺序仍采用原实现。
- 只读审查核对了首屏、后页、历史、短词前缀及原生索引，未发现本轮范围内阻断问题。

## 最终验证
- 命令：`./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand --tests 'com.yuyan.imemodule.data.completion.*' --tests 'com.yuyan.imemodule.data.collect.*' --tests '*T9*Test' --tests '*RimeEngineCompositionStateTest'`。
- 27 个套件、119 项测试，零失败/错误/跳过，日志 `/tmp/shurufa-trusted-regression.log`。
- 未重跑此前因表情界面 OOM 中断的全量测试；未打包/安装、未实机验收，未提交/发布，保留其他工作区改动。
