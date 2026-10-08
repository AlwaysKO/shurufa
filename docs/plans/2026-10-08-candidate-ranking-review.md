# 线上输入诊断核验与近期排序修复计划

> **For Claude：** 使用 superpowers:executing-plans，逐项测试、审查和验证；不自动提交、推送或安装。

**目标：** 核实用户截图建议，先消除一次偶然选词无条件压过基础排序和旧习惯的问题，保留合法候选、学习数据与原生索引。

**架构：** 三处候选排序使用统一的基础先验＋衰减频率＋有界近期加分；近期时间不再作为第一排序键。不改数据库，不新增输入路径 IO，不引入在线模型。

**技术栈：** Kotlin、现有离线候选链、JUnit/Robolectric、Gradle。

## 核验边界
- 截图称 X80 为 20261001.04、另一台为 20261007.21；本轮尚未独立获取两台事件原文，不能称已重放现场或新版真机已验收。
- 当前源码仍在 PersonalCandidateRanker.rank、OfflineT9Candidates.select 短码分支及 rankNative 中优先比较 24 小时内最后选择时间，第 2 项成立。
- CorrectionLearningTracker 只认整词同码和后缀同读音，第 3 项描述的漏判范围成立；不同读音替换也可能是正常改写，暂不自动推广为误选，不扣历史学习。
- 第 1 项需原始编码、候选快照和输入框前文才能定位基础排序与个人权重各自影响；已询问报告/导出位置。不硬编码截图词对，不凭常见程度批量屏蔽人名。

## 检查点 A：有界近期加权
文件：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/completion/PersonalCandidateRanker.kt`、`OfflineT9Candidates.kt`，以及对应 test 目录下 PersonalCandidateRankerTest、OfflinePersonalCandidatesTest、DecoderHabitContractTest。
1. RED：补普通/短码/锁音入口的偶尔选词不压常用词、次日不霸首、渐进衰减、未来时间无加分、反复选择仍有效、原生索引与多音项保留用例。将旧“任意最新一次压过多次历史”的契约按用户本次要求明确修订，不删除其他保护断言。
2. 最小实现：基础先验仍为首项 2、其他项 1/(index+1)，频率仍采用已有 14 天半衰期；额外近期加分初始上限 0.4，30 分钟半衰期，24 小时外归零。上限小于第二项和首项先验差扣一次点击后的差值，防止单次普通选择即强制抢首。此参数为保守修复起点，不冒充线上调参最佳值；明确纠错目前不能仅凭 lastSelectedAt 区分，后续须单独证据。
3. GREEN：三处共享同一评分函数，保留元数据/合法候选/分页原生索引；运行 completion、纠错、inputmethod 关联回归。源码只移除时间优先，不删除学习次数或个人词。

## 后续检查点
- 原始报告到位后回放第 1 项，比较目标词前 3 与首位、重复错误率；轻量上下文方案须只用本次输入框已有安全上下文，不扩采聊天。
- 第 3 项先以同位置、短时间、合法拼写关系作正反例，审查后只限制本笔临时奖励；不把本次排序修复说成纠错范围已扩展。
- 通过测试与代码审查后构建原签名 APK，校验真实版本、非 testOnly 及 E 盘文件 SHA256；设备/版本分别验收。

## 验证命令
`source /home/ko/android-tools/env.sh` 后，在 `android/YuyanIme` 执行 `./gradlew -I /tmp/shurufa-test-memory.gradle :yuyansdk:testOfflineDebugUnitTest --tests '*PersonalCandidateRankerTest' --tests '*OfflinePersonalCandidatesTest' --tests '*DecoderHabitContractTest'`；扩大为 completion 与 inputmethod 分组后执行 `:app:assembleOfflineDebug`。临时日志留 /tmp，不提交诊断内容或截图。

## 回归驱动的方案补充
- 用户随后明确本报告版本为 20261001.04；尚未提供原始候选快照。追问“你也魇为何出现”后，核查当前 t9_lexicon.tsv.gzip、公开 public_phrases.t9idx 均无该精确词串。当前 nativeWhole 允许未收录的完整原生解码，只有拼写/音节与字数检查，存在放行不自然组合的路径；不能据此证明该次来源，亦不能以此为由删掉所有未收录的正常整句。
- RED 55 项中 3 项复现时间优先问题；初步有界分数扩大回归 230 项有 5 项失败，其中 ImeService 的真实严格纠错→下次候选用例揭示必须保留的效果退化，不能修改它来掩盖。
- 增加最小独立纠错提示：只有旧临时奖励实际撤销成功、严格 whole_same_code 且新奖励接收成功后，记录进程内 `(code,text,rewardId,time)`；最多128项、同reward不延长、不累计分数，撤销/限制该reward时移除。普通点选、异码改写、失败撤销均不产生提示。
- 已确认纠错额外加分最多2.0，与普通0.4近期分分别计入，均30分钟半衰期、24小时后为0；足以帮助无/少历史的正确次项，但不无条件压过任意高频习惯。只按实际同码作用，不扩散到其他读音/编码或分段子词。
- 纠错提示只影响本进程短期排序，进程重启后不保留该额外提示；已有学习次数、词条与原有临时奖励持久恢复机制不变。不伪造点击或新增数据库写入。此为避免本次修复伤害已确认纠错的最小方案，不代表第3项识别范围已扩展。

## 2026-10-08 用户批准：第一阶段可信候选与诊断
范围：先完成来源证据分层、有界普通学习、严格纠错独立奖励和随既有成功上屏事件的轻量诊断；不引入语义模型、上下文采集、新数据库结构或联网按键任务。
1. `ConfirmedCorrectionHints.kt` 与同名 Test：先测精确码词、去重、不续期、撤销、容量/过期；实现有界内存提示。接入 `ImeService.learnCommittedSelection`，保留原严格纠错回归不变。
2. `PersonalCandidateRanker.kt` / `OfflineT9Candidates.kt` 与对应 Test：为完整覆盖本次输入的候选标注已收录/明确个人/学习/未验证证据；一次普通选择不能直接晋级稳定学习。只在完整覆盖候选之间调整基础顺序，保留正常未知整句、原生索引与分页。重复使用仍可提升。不把“native”误标为已知原生造句。
3. `CandidateCommitDiagnostic.kt` / `RimeEngine.kt` 与同名 Test：复制排序时已有的证据与分数，不为诊断查询数据库或重新排序，沿用前五项、隐私过滤和成功上屏门禁。无证据的后页如实未知。
4. 串行运行 completion、inputmethod、ImeServiceCommittedEdit 和 PendingLearning 回归；确认普通单选契约与新需求的变化，同时保留召回、前三位、重复学习和严格纠错断言。
5. 审查后原签名构建、APK校验与E盘交付；没有真机结果不宣称无卡顿或现场复现。第二阶段本地搭配排序等待本阶段回放效果，不随本轮扩大实现。

### 实现边界与检查记录
- 保留既有“可信整词存在时不放回陌生原生乱串”的准入规则；本次分层针对已经合法进入候选、但可能只因一次学习被信任的项，不为分层扩大候选准入。
- `lexical_evidence` 表示应用掌握的词面/个人来源依据，不是原生引擎内部出处或语义正确性证明。`native_unverified` 不等于“已确认原生造句”。后续页无计算依据时保留 unknown/null。
- 分层仅交换完整覆盖输入的多字候选的基础槽位；个人权重仍参与最终分数，反复使用仍能提升，不将生僻字或未收录直接判为错误。
- hints 与原严格纠错定向测试通过（/tmp/shurufa-hints-green.log）；可信分层及快照定向测试通过（/tmp/shurufa-trust-green.log）。扩大回归和审查继续进行。
- 本轮 adb devices 返回无已连接设备；未安装、未读取手机私有学习记录，不能认定20261001.04现场来源或宣称真机延迟改善已验收。

### 最终回归与审查（第一阶段）
- `/tmp/shurufa-ranking-final-tests.log`：completion、inputmethod、ImeServiceCommittedEdit、PendingLearning 共242项，0失败/0错误/0跳过；XML副本保存在 `/tmp/shurufa-ranking-verified-results/`（仅本机）。包括原严格纠错不退化、普通/异码/撤销失败不加强、连续纠错撤销旧提示、真实次数、去重补读音证据与异读音隔离。
- 审查指出的去重补读音后新证据字段不同步已用失败测试复现并修复。短码、普通、锁音均先算一次分数再排序，避免比较器内反复扫描提示。
- 旧“一次任意点选必首位”契约按本次授权调整：偶选保留前三位/可召回与原生索引、编码独立次数，多次真实使用仍能提升；原严格纠错首位测试未弱化。
- 之前一次扩大回归出现17项类加载失败，现场存在另一会话同目录Gradle任务，具有共享产物覆盖风险；本轮重跑通过，不能把类加载错误解释为手机运行崩溃。用户同意优先候选验证。
- 工作区另有微信采集会话改动（capture/media及service/capture），未撤销、不归为本轮候选修复。其本机 inflight-green.log 显示相关测试通过；APK从当前共享工作区构建，会包含这些现存改动，不是候选功能的隔离分支包。
- 未覆盖真机输入延迟/长帧、20261001.04现场来源及第二阶段搭配质量；进程内纠错加强重启即失效，持久学习不变。稳定学习阈值2.5是本轮保守起点，不宣称线上最优参数。

### APK 与未验收项
- 共享目录构建因 ScreenshotContentBlocks.class 在任务期间消失而失败；随后只用 /tmp 初始化脚本隔离构建目录和 Gradle 项目缓存，不修改项目构建配置。`/tmp/shurufa-ranking-isolated-build.log` 显示 assembleOfflineDebug 成功。
- E盘测试包：`E:\Projects\shurufa-android\apk\shurufa-2026-10-08-v20261008.15-2026100815-debug-48220a4f.apk`，版本20261008.15 / 2026100815，SHA256 `48220a4f5baa4efb29a535cc0a94bf9077566f6b951f1e8d50742e8be895528a`。
- 已验证 API23/27/28/32/36 原证书、非testOnly、源/交付文件SHA256一致；已删除对应 /tmp WSL APK副本，保留E盘文件。未安装。
- 共享工作区的 PassiveChatAccessibilityService 在预构建快照之后仍由另一会话修改，候选相关生产源码未变。因此本包包含并行采集工作，不宣称隔离的候选专用包或全项目验收完成。
- 另一会话扩大 capture/collect 回归日志还有 InputPriorityWiringTest 的文本接线断言失败，以及 SDK26 Robolectric 依赖下载SSL失败。核对 HEAD 的 WindowScreenshotter 源码也不满足该旧 `!captureAllowed()` 字面断言，不能据此断言本次候选修复导致输入避让失效；但该项仍待对应会话核验修正，不隐瞒全项目未全绿。
- 本轮完成的是候选第一阶段与242项专项回归；APK仅作为待联合/真机验收的测试包，不标为全功能验收通过。第二阶段搭配排序和20261001.04现场来源仍待回放。

## 用户要求继续：联合验证收尾（16时）
- 核对当前工作区后发现，另一会话已经修正InputPriorityWiringTest的旧字面断言，检查onSuccess复制像素前requestAllowed及普通/快帧两路守卫，不再沿用此前“仍待修复”的陈旧结论。
- 本轮仅新增两项候选边界回归：2.5稳定学习阈值及衰减跌破阈值、新临时奖励保存失败不产生纠错加强/点击。候选与上屏生产源码相对首包快照无变化。
- 独立构建目录运行completion、inputmethod、ImeServiceCommittedEdit、PendingLearning及capture/ImageUpload/InputPriority/GameWork/LatestIdleWork：820项，817通过、3失败、0错误/0跳过。日志 `/tmp/shurufa-ranking-combined-tests.log`，XML与summary保存 `/tmp/shurufa-ranking-combined-results/`。失败全为另一会话进行中的新ConversationTitleSimplificationTest，不修改该功能或将3项计为通过。本次候选/输入避让及既有采集关联项全部通过；不宣称全项目GREEN。
- API26定位外部依赖下载不在本次测试筛选范围，未运行项不计入通过。仍未完成本会话的真机候选质量、输入延迟/长帧验证。
- 另一会话后续已交付同版本5d56c1b4包并记录覆盖安装。为避免仅补测试就重复换包，本轮复核E盘该包：完整SHA256 `5d56c1b4d892d6042dc58831a7d41f0263999560954724fd1ade1ac0ae91bec6`；API23/27/28/32/36原证书、非testOnly。
- 使用apkanalyzer逐类对比首包48220a4f与后续5d56c1b4，PersonalCandidateRanker、ConfirmedCorrectionHints、OfflineT9Candidates、CandidateCommitDiagnostic及Kt、RankedCandidate、RimeEngine、ImeService共8个核心类的DEX反汇编完全一致。报告在 `/tmp/shurufa-ranking-apk-code-comparison.json`。这证明后续包保留本轮候选实现，不把它扩展成整个APK相同或聊天新功能全验收的结论。
- 本轮候选交付沿用：`E:\Projects\shurufa-android\apk\shurufa-2026-10-08-v20261008.15-2026100815-debug-5d56c1b4.apk`，取代本计划此前48220a4f推荐；若已经安装该包，无需因本轮补测重装。本轮没有新增生产行为、重新打包、安装、提交或推送。
- 已请求用户在输入框试打“你怎么/想开点/开始”，无需发送，异常时提供拼音与候选栏，作为第二阶段轻量搭配排序的实际回放证据。不将本地测试冒充20261001.04历史事件来源已确认。
