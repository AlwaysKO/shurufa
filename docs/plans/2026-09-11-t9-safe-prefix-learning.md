# 安全末字补全与跨编码学习修复计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 按任务执行。

**目标：** 保留防乱拼限制，支持 9366 的怎么、64324862 的你发货吧，并把补完整后的明确选词用于同读音合法末字短码。
**架构：** 仍禁止内部音节简拼及四字以上纯首字母扩写。仅将末字补全的最低总长度从 6 降到 4，三键候选策略不变。高频或自维护的合法补全词可优先于未收录原生首项，低频词不强推，原生原序保留；后者不一律删除。SQLite 只读相关编码的历史，经过当前候选真实拼音校验后聚合衰减权重，不复制学习事件，不修改表结构。
**技术栈：** Kotlin、SQLite、JUnit/Robolectric、离线 Gradle 测试。

## 来源与边界
- 用户 2026-09-11 本轮明确要求：保留原来防莫名其妙词语的限制，同时修复常用词召回、短码学习。
- 沿用 docs/android-integration.md 的隐私边界、同码学习衰减、原生索引和分段/锁拼音边界。
- 本轮取代“所有末字补全都排在原生后面”的排序策略，不取代真实读音过滤。不删除夜魔或其他合法生僻词；不声称具有通用句子语义判断能力。
- 不改无关未提交工作，不提交/发布/安装 APK。

## 任务 1：失败用例
文件：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/completion/OfflinePersonalCandidatesTest.kt`、`.../inputmethod/util/T9SpellingTest.kt`、`.../data/collect/LocalInputStoreTest.kt`。
验证：9366 首屏怎么优于夜魔；93663 选择能影响 9366 并重开数据库保留；跨编码历史不能扩写内部音节、不能重复累计同次事件、不能影响其他词/三键或字母码；64324862 返回你发货吧，真实拼音不符的灭除妈啊被过滤；充电宝与原来不高兴/长条形等反例一起回归。明确更新原先后远啊优先的旧排序断言。
运行：`source ~/android-tools/env.sh; cd android/YuyanIme; ./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand --tests '*OfflinePersonalCandidatesTest' --tests '*T9SpellingTest' --tests '*LocalInputStoreTest'`。

## 任务 2：最小实现
- `.../inputmethod/util/T9Spelling.kt`：从合法读音生成仅末音节可截短的编码集合，总长度至少 4，不生成内部简拼。
- `.../data/collect/LocalInputStore.kt`：新增带来源编码的相关历史查询，不做数据库升级、不改旧学习和报告写入。
- `.../data/completion/OfflineT9Candidates.kt`：仅合法兼容读音共享选择权重，保留原生索引及短三码策略；调整已收录词与原生回退结果的先后。
- `.../assets/completion/chinese_domains.tsv`：补入用户明确表达“你发货吧 / ni fa huo ba”，不硬编码数字或拉黑字串。

## 任务 3：验证与记录
先红后绿，运行相关 completion、collect、T9 与 RimeEngine 测试；鉴于上一轮全量表情测试 OOM，不把相关测试当全量通过。只读审查改动，追加集成文档中明确取代规则与验收边界。

## 调试与设计收紧记录
- 第一组旧实现验证：15 项中 4 项预期失败（怎么短码召回/长码学习、你发货吧、旧后远啊优先策略），日志 `/tmp/shurufa-safe-prefix-red.log`。
- “词库存在就提前”的初版被既有不高兴反例拦截：低频不好战（频率 3）抢首位。因此提前仅限主词库频率至少 1000（当前 130860 条中的 5202 条）或自维护词库；该值为保守排序策略，不是语义正确性的保证。低频词仍显示、可选择学习。
- 4/5 键低频完整本地词不强抢原生；6 键以上完整本地词优先保留旧行为。
- 只读审查发现并用失败测试验证：原生第 9 条被截断误认未收录；同文字不同读音编码集合不能先取并集。已分别改为额外查询原生文本、按单个读音集合校验两码。
- 原生候选不再全局按收录/未收录重新分组；保持其原序，只在过滤后首项未被本地合法收录时将高频/自维护末字补全前置，防止原生第二位低频词仅因收录抢到第一。
- Android 28 下 SQLiteOpenHelper 的 Kotlin AutoCloseable use 测试触发 ClassCastException，测试改为 try/finally close，不改生产关闭逻辑。
- 相关历史查询走编码索引，不增加表和重复记录；大量同四码前缀历史的逐键性能仍需长期使用压测，不随意加 LIMIT 丢掉有效强习惯。

## 最终验证
- 相关回归命令：`./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand --tests 'com.yuyan.imemodule.data.completion.*' --tests 'com.yuyan.imemodule.data.collect.*' --tests '*T9*Test' --tests '*RimeEngineCompositionStateTest'`。
- 结果：27 个套件、114 项测试，0 失败、0 错误、0 跳过。日志 `/tmp/shurufa-safe-prefix-final.log`。
- 新增保护例均经红绿验证：原生第九条、不同读音并集、四五键低频完整词、低频词同时在原生第二项。只读审查的已知问题均已按测试修复。
- 本轮未再跑上次因表情界面 OOM 中断的全量测试；未生成/安装 APK，未实机验收，未提交/发布。
