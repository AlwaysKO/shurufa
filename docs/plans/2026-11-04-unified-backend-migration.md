# Android 统一后台地址迁移实现计划

> **For Claude：** 使用 superpowers:executing-plans 按任务实施，遵循 test-driven-development 与 verification-before-completion；当前分支开发，不自动提交、安装或发布。

**目标：** 后台下发一次地址变更后，普通上报、使用记录、通话授权与录音待传统一切换并可中断恢复。

**架构：** ServerConfig 保留唯一线上地址，地址和迁移日志同一 SharedPreferences commit。协调器按日志顺序幂等迁移普通队列、使用记录、录音待传与已有授权，完成后持久化进度并通知 Job。保留地址历史用于晚结束录音和系统导入索引查重；不重放已确认历史。

**授权依据：** 2026-11-04 用户明确“是”：后台有效配置下发的新地址视为同一业务后台迁移，可自动迁移已有授权，不重新开启关闭项。取代 CallRecordingConsent 对此特定入口的旧“不静默迁移授权”限制；手工无关地址不自动迁移。

**技术栈：** Kotlin、SharedPreferences、SQLite 事务、AtomicFile、Robolectric、Gradle。

## 任务 1：失败测试
- 新增 `android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/collect/BackendAddressMigrationTest.kt`。
- 验证已有授权和关闭状态、设备隔离、录音 ID/指纹/字节保留、旧回执不用于新目标清理、重启幂等恢复、使用记录迁移、无效地址拒绝。
- 在 Android 根加载 `/home/ko/android-tools/env.sh`，运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*BackendAddressMigrationTest' --offline --console=plain`，先记录缺失功能导致的 RED。

## 任务 2：最小实现与边界回归
- 修改 `data/collect/ServerConfig.kt`、`DataCollector.kt`；新增 `data/collect/BackendAddressMigration.kt`（路径均相对 yuyansdk/src/main/java/com/yuyan/imemodule）。
- 修改 `data/callrecording/CallRecordingConsent.kt`、`CallRecordingOutbox.kt`、`CallRecordingRuntime.kt`、`CallRecordingSessions.kt`、`SystemRecordingImporter.kt`，及 `data/calllog/PhoneCallLogConsent.kt`、`PhoneCallLogRuntime.kt`、`data/usage/UsageStore.kt`。
- 顺序：原子保存地址/日志 → 迁移队列（事务或原子清单）→ 迁移同设备同旧地址的授权、递增 revision → 持久进度 → 唤醒。任何失败保留日志和音频，后续原入口重试。
- 上传捕获目标和地址 epoch，切换时取消旧轮次；迁移与上传共用批次互斥锁。不复制音频，不更换 UUID，不将旧目标的 saved 回执冒充新回执。
- 录音会话发布时解析历史迁移，覆盖切换时正在封装的旧目标任务。系统索引按地址历史查重，保留旧索引，无需删除原件或重扫全部音频。
- 补充失败测试覆盖上述晚到会话、系统导入、目标回切和坏回执；运行 RED 后最小修复至 GREEN。

## 任务 3：验证、审查与交付
- 运行迁移、callrecording、calllog、usage、collect 与聊天采集相关单元回归，读取 XML 实际计数；运行 `git diff --check`。
- 原签名 `:app:assembleOfflineDebug -Pandroid.injected.testOnly=false`；按 SIGNING.md 核对 APK 版本、签名、testOnly、交付 SHA256。
- 交付 `/mnt/e/Projects/shurufa-android/apk/`，校验后清理本次 WSL APK 副本。不连接真机，不声称微信/抖音或通话端到端验收通过。
- 更新本计划实际证据；AGENTS.md 仅保存用户此次清楚授权的长期迁移边界。不自动提交推送。

## 实施检查点与补充设计

- 首轮 RED：缺少 BackendAddressMigration、UsageStore.replaceTarget、Outbox.replaceTarget、会话/导入索引迁移接口，Gradle 编译测试失败，日志 `/tmp/shurufa-migration-red.log`。
- 独立代码审查确认需覆盖：延后普通事件/会话入队旧快照；旧 saved 回执对应的私有副本清理与同次通话防重；新端已有更新 usage evidence 时不可被旧快照覆盖。
- 入队获得 SQLite 写事务或 outbox exclusive 后才解析历史地址；迁移写者共享相同队列写锁，从而保证先入后搬或先搬后用新地址。不能持 ServerConfig monitor 等待数据库或计算音频哈希，避免与学习嵌套事务发生锁反转。地址读取不等待业务写锁，不将任务移到键盘线程。
- 旧 saved 任务保持原目的地和真实回执，不伪造成新端 saved；仅在受信迁移历史确认同业务目标时，允许按原回执清理原私有副本或按原同次通话规则清理明确重复片段。
- 兼容升级前已经切换地址但未迁移通话授权的遗留状态：仅同设备、合法 HTTPS 的既有授权绑定可补建到当前后台的迁移日志，开关及权限不变。与普通双传的本地镜像无关，不将任意历史镜像当旧线上后台。
- 通知与 applied 进度分开持久化，避免进度提交后进程死亡使待传 Job 永远不唤醒。

- 第二轮独立审查发现的全局锁反转与连续录音授权间隙均已新增行为用例观察到 RED（19 项中 2 项失败，日志 `/tmp/shurufa-migration-final.log`），随后移除业务写入的全局配置 monitor，按队列写锁解析历史，并让已有录音授权在持久迁移历史内平滑衔接。

## 验证记录（2026-11-04）

- 迁移专项第一轮 GREEN：19 项、0 失败、0 跳过，XML 两个 suite 为 BackendAddressMigrationTest(13) 与 BackendMigrationQueueTest(6)。随后新增“未收到后台地址时不按打包默认地址补迁”的授权边界测试，观察到 20 项中该 1 项 RED 后增加显式配置门槛。
- 扩大回归：1923 项，1891 通过、32 失败、0 error/skip，11 个失败类。日志 `/tmp/shurufa-migration-regression.log`，当次 XML 汇总保存在 `/tmp/shurufa-migration-broad-results.json`。**没有全量通过，不将这些失败全部推断为既有问题，也不把测试包标为已验收发布。**
- 已直接从 HEAD 核对的既有不一致：CallRecordingSettingsDisclosureTest 的标题断言是“输入记忆与上传”，HEAD Activity 实际为“音频与文件”；PageFrameProbeTest/LocalPageFrameReaderWaitTest 断言诊断日志，但 HEAD 已默认要求主动开启诊断，后者新鲜 trace 指向第63行日志断言。
- CollectorRecoveryTest 的 294000/240000 对 300000 调度差异，以及立即读取异步预约 Job 的竞态，HEAD 也已有对应路径；本次未顺手改变上报节奏。微信结束预约用例存在 recentEnd 先可见、Job 后预约的异步窗口，仍待隔离复跑确认。
- LocationMotionWakeTest 的14个失败为 fixture 的 motion listener 未注册；其他聊天通知/媒体测试另有 SparseArray/断言失败。相关生产路径未改，但本轮未完成 HEAD 对照，**不声称已证明其与改动无关**。不为了全绿关闭断言、放宽采集或改无关业务。
- 遗留补迁仅在存在已接收并持久化的有效 online_server_url 时启用，不以 APK 默认地址代替后台下发迁移授权。
- 未连接手机、未安装、未上线/部署、未提交或推送。微信/抖音聊天识别、截图落盘、Wi-Fi回执、线上会话/图片、真实通话、重启和覆盖安装保持均尚未真机验收。

### 最终专项与测试包交付

- 最终专项20项（14+6）、0失败/错误/跳过；`git diff --check` 通过。命令：加载 `/home/ko/android-tools/env.sh`，以 `/tmp/shurufa-migration-tests.gradle` 仅包含两个迁移类，运行 `:yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug --offline --console=plain -Pkotlin.daemon.jvmargs=-Xmx3072m -Pandroid.injected.testOnly=false`；构建成功，日志 `/tmp/shurufa-migration-build.log`。
- 独立只读审查最后未发现新增明确 P1；审查不代替测试。扩大回归32项失败仍未全量解决/对照，因此交付仅为开发测试包，不是已验收发布。
- 唯一推荐测试APK：`/mnt/e/Projects/shurufa-android/apk/shurufa-2026-11-04-v20261104.10-2026110410-debug-402f2ff1.apk`；版本 `20261104.10` / `2026110410`，包名 `com.yuyan.pinyin.offline.debug`。
- SHA256：`402f2ff1b2f1aac9d39ebedca7f319d72b1cae35c975ad1694f52e99429db361`。源/交付文件一致；API23/27/28/32/36均为固定原签名，非testOnly，APK badging版本与构建元数据一致。验证完成后仅清理上述相同SHA的WSL APK副本，E盘文件保留。
- 跨独立数据库的严格防重仍依赖后台迁移保存原ID、内容索引与回执；客户端保持UUID/指纹并先查询新端真实回执，不承诺未迁移后台幂等数据时仍能跨库exactly-once。
