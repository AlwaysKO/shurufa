# 本地优先九宫格候选与双端补传实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 离线支持全拼、简拼与混合九宫格候选，持久保留选词习惯；输入事件本地落盘，并独立补传电脑和线上。

**架构：** 保留 Rime 原生解码和用户词库，在完整未分段九宫格输入前增加有词频的本地词典候选；用户选词按原始数字编码学习，不把展示的首选拼音当输入。独立 SQLite 存储选词频率及每个目标的事件确认状态；网络失败不影响解码。修复 CompletionSync 候选与版本分离持久化的问题。

**技术栈：** Kotlin、Android SQLiteOpenHelper、JUnit/Robolectric、OkHttp MockWebServer、Node（既有 pinyin-pro，仅构建词库使用）。

## 已确认的事实与边界

- 用户复现 `46898262` = `hou xuan c`；完整拼音为 `468982624`，需覆盖混拼，不能硬编码一组数字。
- RimeEngine 当前直接使用原生首选及其 comment 显示组合串；未接入本地混拼补充排序。
- 搜狗 APK 的 IMEInterface 有 learnWord、learnWordWithMode、saveUserDict，调用 IMECoreInterface；仅借鉴本地学习/持久化边界，不提取或分发专有词库/算法。
- 原有上报仅内存队列且单一 baseUrl；电脑 API 和 5175 正常。设备目录显示真机最后注册为 2026-08-31；本轮 ADB 无设备，不能声称真机修好。
- 线上使用用户指定 https://my.dog8ball.com；当前证书域名校验失败，不关闭 TLS 校验。仅输入事件和设备注册双传，不扩大到聊天截图、位置等数据。
- 当前工作区存在用户/其他任务的候选 UI、表情功能修改，保留它们，不整文件回退、不打包提交无关改动。

## 任务 1：离线候选与选词学习

文件：新增 `data/completion/T9Lexicon.kt`、`OfflineT9Candidates.kt`；修改 `inputmethod/RimeEngine.kt`、`data/KeyRecordStack.kt`、`application/Launcher.kt`、`service/ImeService.kt`；新增对应测试和 `tools/generate_t9_lexicon.mjs`、资产及许可证。

1. 测试 `hou xuan ci` 能以完整、首字母、混拼命中；其他词同样适用；不合法输入、分段/拼音锁定不得覆盖 Rime。
2. 运行 `:yuyansdk:testOfflineDebugUnitTest --tests '*T9*Test'` 看见预期失败。
3. 实现按首音节数字分桶、音节动态匹配、词频排序；引擎保留原生候选索引，补充候选可直接提交。
4. 加入成功上屏才学习、密码/禁止个性化不学习、重启恢复和同码排序测试，失败后实现 SQLite 持久化。
5. 从固定版本 MIT jieba 词典生成离线拼音资产，保留来源和许可证；生产查询无网络调用。

## 任务 2：事件持久化与双目标确认

文件：新增 `data/collect/LocalInputStore.kt`、`EventDelivery.kt` 及测试；修改 `DataCollector.kt`、`ServerConfig.kt`。

1. 测试重启保留、同一事件 id、一个目标成功另一个失败不互相阻塞、全部确认后清理、注册失败不丢事件。
2. 运行对应单测确认失败。
3. SQLite 事务入队，按目标发送、确认、重试，发送前确保设备注册。线上固定用户指定域名，本地沿用 server_url/127.0.0.1:3000；不修改其他模块 baseUrl。
4. 运行单测，MockWebServer 检查协议和独立重试；日志只含目标/数量/状态，不输出输入文本。

## 任务 3：云补全缓存与交付验证

文件：`data/completion/CompletionSync.kt`、新缓存测试、`docs/android-integration.md`。

1. 测试缓存与版本一起恢复，旧版本号无缓存必须从 0 同步，按服务地址隔离；旧服务端分页协议另行评估。
2. 失败后实现原子文件缓存，关闭 feedback 响应资源。
3. 运行新测试与相关引擎/提交测试、完整 SDK 单测及 Debug APK 构建；必要时记录无关已有失败。
4. 自审改动、必要代码审查、`git diff --check`；给出 APK 与真机 adb reverse/断网验收步骤。线上 TLS 和无设备问题明确列为外部待验证项。

## 实施验证记录（持续更新）

- 词库生成器 Node 单测：1 通过；真实资产 130,860 词条，gzip 约 1.3 MB。
- 独立 JVM 候选/提交交接单测：6 通过，包含真实资产 `46898262` 和 `468982624` 首选“候选词”。早期测试实际捕获了“机械优先完整拼音导致后远啊压过高频混拼词”的排序问题，调整为词频与简拼惩罚共同评分。
- KSP 2 测试阶段报告已有 Windows `E:\\Projects\\...ExpressionCatalogTest.kt` 路径；隔离旧 Unix 产物仍存在。测试源未包含 @Database/@Dao/@Entity，验证采用仅禁用测试 KSP 的临时 init 脚本，生产 KSP 保持开启；未修改项目构建配置。
- 代码审查发现旧云联想协议同批共享 version、非事务全量重建且没有删除同步，单加页末游标无法正确修复。撤销试验性的分页循环/游标测试，本轮只修缓存与版本一起重启恢复；has_more 响应保留旧快照而不冒充完整同步。完整原子快照/分页协议留待后续，离线九宫格和事件双传不依赖此链路。
- 22 点后复查：本地与 `https://my.dog8ball.com/health` 均 200 且线上为 `{"status":"ok"}`；线上 mobile/completions 未带设备身份返回预期 400。早先的 TLS 域名错误本次未再出现。ADB 仍无设备；没有向真实数据库写入模拟输入记录。
- 短码体验保护：本地常用词在少于 6 位时只补充全拼匹配，避免输入 `hou=468` 时把“宫女”等多字简拼词压在原生单字前；已有个人选词仍可优先。手工常用语仍排在新增普通本地词之前。
- WSL 桌面 JVM 测量（不是手机性能承诺）：词库热查询 p50 约 1–3 ms，所测编码 p95 约 1.4–8.8 ms；手机逐键延迟待实际设备验收。
- 全量测试首次遇到 Robolectric 资源加载 OOM（默认测试堆较小），未将其当成业务成功。最终验证改用 1536 MB 测试堆、每 10 个测试类重启进程、单测试进程，避免跨大量 SDK 资源沙箱累积。

临时验证 init 脚本（仅验证环境，不修改项目编译配置）：

```groovy
// /tmp/shurufa-test-verification.gradle
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

```bash
source ~/android-tools/env.sh
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug \
  -I /tmp/shurufa-test-verification.gradle --configure-on-demand --offline
```

## 最终结果

- 最终全量 SDK 单测：78 个测试类，458 项测试，0 failures / 0 errors / 0 skipped。
- `:app:assembleOfflineDebug`：成功（与全量单测同一 Gradle 调用，6m29s）。测试环境设置见上文。
- 最后独立 JVM 复核：7 项通过；Node 词库生成器测试：1 项通过；`git diff --check` 通过。
- APK：`artifacts/local-first-t9/shurufa-local-first-t9-20260907.apk`，包名 `com.yuyan.pinyin.offline.debug`，版本 `20260907.22`。
- APK SHA-256：`456dadddf0eef5002d4cef6e3bd9c63be011f990c08366e20c1ef4ab09a31cf0`。
- apksigner 验证成功（v1/v2）；APK 中词库内容与源资产逐字节一致，许可证及新增 OfflineT9Candidates / LocalInputStore / EventDelivery 类均存在。签名工具同时报告既有 META-INF 条目的 v1 覆盖提示，不影响 v2 整包校验成功。
- 未修改用户已有的 CandidatesBarAdapter.kt / CandidatesBar.kt 两行变更；未自动提交、推送或部署服务端；未生成/分发搜狗专有词库。
- 待真机验收：安装新包、离线九宫格输入与学习恢复、电脑 adb reverse、两端实际入库。当前 ADB 无设备，未宣称真机链路已经验证。
