# 九宫格近期改选与常用词排序实现计划

> **For Claude：** 使用 superpowers:executing-plans、test-driven-development 和 verification-before-completion，在当前目录实施，不提交或安装。

**目标：** 修复用户已确认的 966 最近改选“我们”仍被“我哦”压住，保留其他正常学习能力。

**架构：** 保留原生解码、合法拼写过滤与原生索引。个人排序增加同码近期明确选择优先，其他情况仍按衰减权重；三键基础候选取消原生首项无条件优先，使用已有常用词词频。合法末音节补全的学习共享扩展到三键，但不扩大自动组句的匹配资格。

**技术栈：** Kotlin / SQLite / Rime / JUnit / Robolectric。

## 证据及边界
- 已通过 Windows ADB 只读检查安装版本 20260922.15。针对“我们/我哦”的学习记录证明同码学习存在；旧词累计更多、基础首项加分、三键不共享完整码共同影响排序。临时数据库副本已删除。
- 用户明确同意：主动改选后，下次优先；旧习惯仍随时间衰减。不删除学习记录、不伪造次数。
- 本批不引入新词库、不实现容错；现有词库已含常用词“我们”，先修复排序使用方式。公共词库更新及 286239 → 错别字作为下一批独立任务。
- 近期明确选择优先窗口采用 24 小时，过期回到原衰减排序；同一毫秒并列按原权重及基础序列决定。该窗口是本次工程参数，不写成用户长期规则。

## 任务 1：先写失败测试
文件均在 android/YuyanIme/yuyansdk/src：
- test/java/com/yuyan/imemodule/data/completion/PersonalCandidateRankerTest.kt：最近明确选择胜过高频旧词，过期不永久置顶，索引保留。
- test/java/com/yuyan/imemodule/data/completion/OfflinePersonalCandidatesTest.kt：966 默认我们优于我哦；旧我哦4次后我们1次立即优先，重开数据库保持；完整码与三键合法共享、不重复写记录。
- test/java/com/yuyan/inputmethod/util/T9SpellingTest.kt：保持原有组句边界；学习共享允许末音节三键，不允许内部简拼。
运行定向 Gradle 单测，确认断言失败而非环境失败。

## 任务 2：最小修复
- main/java/com/yuyan/imemodule/data/completion/PersonalCandidateRanker.kt：增加可选真实选择时间与近期优先比较，不影响未选择及全键旧策略。
- main/java/com/yuyan/imemodule/data/completion/OfflineT9Candidates.kt：传递真实同码 lastUsed；三键常用词优先，保持精确单音节原生次序；锁音保留逐条索引。
- main/java/com/yuyan/inputmethod/util/T9Spelling.kt、main/java/com/yuyan/imemodule/data/completion/PersonalWordReading.kt：学习单独允许三键末音节补全。
- main/java/com/yuyan/imemodule/data/collect/LocalInputStore.kt：相关学习查询允许三键；只修改对应行，保留其他会话已改的上报逻辑。

## 任务 3：回归及交付
- 按新需求更新明确被替代的旧测试，不放宽其他边界断言。
- 运行 completion、T9Spelling、选择提交相关回归；必要时分批控制 Robolectric 内存。
- 审查 diff，构建 :app:assembleOfflineDebug，验证原签名、非 testOnly、真实版本、交付 SHA256。
- APK 放 E:\Projects\shurufa-android\apk；不自动安装，不声称真机候选已验收。

## 执行记录
- 计划已建立，开始 TDD。尚未修复或交付。

### 第一阶段验证与交付
- TDD：初始 OfflinePersonalCandidatesTest 38 项，新增最近改选、完整码共享、三键默认排序共 3 项按预期失败；修复后通过。旧“原生首项固定优先/三键不共享”断言按用户最新规则替代。
- 最终定向回归 23 类、135 项，0 失败/错误/跳过。包含候选/锁音索引、近期选择、跨码时间隔离、三键匹配、提交隐私与成功门禁、个人词库存储及增量下发。记录 /tmp/shurufa-t9-delivery.log，先失败 XML /tmp/shurufa-t9-red-results。
- 独立只读审查未发现阻断；已补“需求/续期均存在”断言以防 indexOf=-1 假通过。
- Gradle :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug 成功；现有 compileSdk/弃用警告保留，未扩大修改范围。
- 交付：E:\Projects\shurufa-android\apk\shurufa-2026-09-22-v20260922.16-2026092216-debug-2563bd26.apk
- 包名 com.yuyan.pinyin.offline.debug，versionName 20260922.16，versionCode 2026092216。
- 源包/交付包 SHA256 均为 2563bd261014a45b6de6c37465b7d6dc1fa0869e73cad1626018aaacdf3cf15f。
- tools/verify-delivery-apk.py 验证 API23/27/28/32/36 均为固定原证书、非 testOnly。
- 未安装到手机、未清理手机数据、未提交或推送。真机新包候选仍待用户验收；当前工作区包含其他会话上报改动，保留未覆盖。
- 本阶段仅改善已有公共词频的三键排序及个人学习；未导入新的公共词库，未实现拼音容错。后者须独立实施与验证，不将本包称为纠错版。
