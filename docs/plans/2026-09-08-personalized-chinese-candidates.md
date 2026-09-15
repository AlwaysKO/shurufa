# 安卓中文候选个性化实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 安卓九宫格及标准全键拼音离线学习中文选词，常选词逐渐靠前，补齐聊天、办公、互联网中文领域。

**架构：** 保留 Rime、现有十三万本地词与持久补传。纯排序器合并本地与原生候选，携带原生索引；按编码用平滑基础先验加十四天半衰期的选中权重计算相对选择概率。SQLite 无损升级保留累计次数和待传事件，只在成功上屏且允许学习时更新；不增加英文或电脑端功能。

**技术栈：** Kotlin、SQLite、Rime、JUnit/Robolectric、Node 资产生成。

---

### 任务 1：可验证的排序规则
- 创建 `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/completion/PersonalCandidateRanker.kt` 与同路径 test 源下 `PersonalCandidateRankerTest.kt`。
- 先测试：无历史不变、一次选择不覆盖强默认、重复选择需求超过续期、历史衰减、去重保留原生选择索引。
- 运行 `/tmp/shurufa-pure-tests.sh` 扩展版，确认缺失实现失败，再实现最小纯函数，运行至绿。
- 基础首选先验 2，其余 1/(index+1)，历史权重每十四天减半；按先验+权重排序（共同归一化分母不影响顺序）。

### 任务 2：历史数据库升级
- 修改 `data/collect/LocalInputStore.kt`、测试 `LocalInputStoreTest.kt`。
- 先写并运行失败测试：字母编码可记中文、重启权重保留、旧表升级保留事件与历史、旧权重衰减再加一次，而非刷新累计次数。
- 新增 weight 字段，旧 count 迁移为 weight；last_used 作为权重更新时间，保持累计 count。接收数字编码或小写拼音，拒绝混杂编码及非中文文字。
- 运行 Gradle targeted tests，预期全部 PASS。

### 任务 3：输入链路与领域词库
- 修改 `OfflineT9Candidates.kt`、`T9Lexicon.kt`、`inputmethod/RimeEngine.kt`、`inputmethod/data/KeyRecordStack.kt` 与对应测试。
- 先验证标准拼音混拼召回、短九宫格保护、键栈锁定不学习、候选选择索引不变；再实现。
- 排序后的首屏保存原生索引映射，翻页仍用原生后续索引；本地补充候选才直接提交。手工候选顺序不动，双拼/英语/繁体不纳入新增模型。
- 添加审核中文领域 TSV 资产，复用现有拼音词典加载；测试常用领域代表词可离线查询。不得复制搜狗专有资产。

### 任务 4：审查、验证、交付
- 使用 requesting-code-review 的只读审查，修复确定问题。
- `source /home/ko/android-tools/env.sh; ./gradlew :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug -I /tmp/shurufa-test-verification.gradle --configure-on-demand --offline`，预期 BUILD SUCCESSFUL；记录实际类数与用例数。
- 检查新 APK 签名、资产、SHA256，保存独立 artifacts 目录，更新集成文档。
- adb 无设备时明确未真机验收，不模拟生产输入事件。保留现有未提交修改，不自动提交用户文件。

## 执行结果（2026-09-08）

- [x] 任务 1：纯排序器、去重及原生首屏/翻页选择映射；先验证缺失 API 的红，再通过纯 Kotlin 回归。
- [x] 任务 2：v2 无损迁移、旧权重衰减再累加、标准字母编码、重启持久化。迁移测试最初因 API 28 不支持 helper AutoCloseable 的测试夹具失败，改显式 close 后全绿。
- [x] 任务 3：九宫格和标准全键中文接线；新增 347 条领域资产（304 个新增不重复词）；实际资产与 SQLite 的集成查询回归通过。
- [x] 任务 4：只读审查无确定重要问题；全量 80 类/473 项测试零失败、APK 构建及签名通过，6m27s。纯 Kotlin 16 项、Node 1 项通过。
- [ ] 真机验收：ADB 无设备；本地与线上健康接口正常，但未制造或声称真实手机双端上传成功。

产物：`artifacts/personalized-chinese/shurufa-personalized-chinese-20260908.apk`。包名 `com.yuyan.pinyin.offline.debug`，版本 `20260908.00`，SHA256 `84359852aa008d1416586ae5045508d9e177c5811816713cf234250e3b12eec3`。保持当前分支未提交状态，不合并、不推送、不改用户原有两处 UI 修改。

测试 init script（`/tmp/shurufa-test-verification.gradle`）：

```groovy
gradle.projectsEvaluated {
    def sdk = gradle.rootProject.project(':yuyansdk')
    sdk.tasks.named('kspOfflineDebugUnitTestKotlin').configure { enabled = false }
    sdk.tasks.withType(Test).configureEach {
        maxHeapSize = '1536m'
        maxParallelForks = 1
        forkEvery = 10
    }
}
```
