# 熄屏位置与非图片批量上传实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 非 Wi-Fi 每分钟检查有效位置变化并保存，两网非图片待传按 5 分钟合批，补齐熄屏恢复且避免空轮询。

**架构：** 复用均衡定位服务、现有持久队列和 UploadRecovery 系统任务；采样、入队和上传分离。待传类别决定下次到期，屏幕事件只触发重新检查，不绕过批次、授权或失败保护。

**技术栈：** Kotlin、Android LocationManager/JobScheduler、协程、JUnit/Robolectric、Gradle。

---

## 交付状态（2026-10-15）

任务 1–4 及追加图片依赖修复已实现并通过两阶段审查；任务 5 自动化对照、构建与签名交付已完成，真机安装/熄屏时效/耗电验收待用户执行。全量存在 6 项已用 HEAD 实测确认的既有失败，不能称全量全绿；完整证据见末尾。

## 执行约束

- 先阅读同名 `-design.md`、根 AGENTS.md 与目标子目录适用规则，在当前分支执行；不创建 worktree。
- 计划编写时尚未修改生产代码；现已实施，截图事件根因仍未经真机时间链证实。
- 每个任务按 @superpowers:test-driven-development：补测试 → 实际观察预期失败 → 最小实现 → 复验。禁止只更新旧断言使旧行为看起来通过。
- 修改前读完整相关实现与测试；以下路径相对项目根。`MAIN` 为 `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data`，`TEST` 为同模块 `src/test/java/com/yuyan/imemodule/data`。
- Gradle 命令在 `android/YuyanIme` 运行。先检查本机 SDK/JDK/签名配置，不输出凭据。测试任务以实际 `tasks --all` 核验；预期使用 `:yuyansdk:testOfflineDebugUnitTest`。
- 不自动 Git 提交或安装。每项完成记下测试命令、数量、失败原因和证据路径。

## 任务 1：按网络决定位置采样节奏

**修改：** `MAIN/collect/BalancedLocationPolicy.kt`、`MAIN/collect/BalancedLocationService.kt`。
**测试：** `TEST/collect/BalancedLocationPolicyTest.kt`、`TEST/collect/BalancedLocationServiceTest.kt`。

1. 补失败用例：非 Wi-Fi 连续静止仍 60000ms；Wi-Fi 稳定后 300000ms；Wi-Fi 丢失立即恢复 60000ms；同一 Wi-Fi 下可信连续移动不能被锁为静止；无回调确认窗口到期恢复正确网络节奏。
2. 运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*BalancedLocation*Test' --offline --console=plain`，确认新增断言失败而非环境错误。
3. 最小实现网络感知的有效采样间隔，保留地理运动状态及质量过滤。服务复用有效 Wi-Fi 判断，在网络变化时按需重新注册；避免每次回调重复注册和新增轮询。销毁时注销网络观察者。
4. 保留短时确认窗口上限；非 Wi-Fi 正常周期不因静止退到 5 分钟。屏幕状态不决定位置采样频率。
5. 重跑上述测试，补网络回调注销/重复注册测试，确认服务关闭与权限撤销仍停止请求。

## 任务 2：有效位置按分钟持久化，不在网络批次前丢点

**修改：** `MAIN/collect/LocationUploadPolicy.kt`、按需修改 `MAIN/collect/DataCollector.kt`。
**测试：** `TEST/collect/LocationUploadPolicyTest.kt`、`TEST/collect/BalancedLocationPolicyTest.kt`；复用现有 DataCollector 持久化测试，必要时补 `TEST/collect/LocationBatchRetentionTest.kt`。

1. 补失败用例：首次有效点后 60 秒的新有效位移允许入队；不足 60 秒限制重复密集报告；5 分钟内 5 个有效变化点独立保存，上传不把它们覆盖为最后一条。
2. 关键断言示例（沿用测试中的 point/start helper）：
   ```kotlin
   val last = UploadedLocation(23.13, 113.3, 15f, start, start)
   assertTrue(LocationUploadPolicy.shouldUpload(
       start + 60_000, point(start + 60_000, 23.14), last))
   ```
3. 运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*Location*Test' --offline --console=plain`，确认旧 300000ms 入队门槛使新用例失败。
4. 将入队最小节奏与上传 5 分钟分离，保留准确度≤50m、有效新鲜定位、位移和跳点判断。不无关重命名持久字段；注释说明历史 uploadedAt 实为入队基准。
5. 补持久化失败不推进基准、静止不新增、重启保留已有点、occurredAt 使用定位时刻的测试；确认此前防漂移测试不退化。

## 任务 3：统一非图片 5 分钟批次及待传到期查询

**修改：** `MAIN/collect/CollectorUploadPlan.kt`、`MAIN/collect/CollectorSyncCadence.kt`、`MAIN/usage/AppUsageTracker.kt`。
**测试：** `TEST/collect/CollectorUploadPlanTest.kt`、`TEST/collect/CollectorSyncCadenceTest.kt`、`TEST/usage/UsageUploadTest.kt`、`TEST/usage/UsageUploadRecoveryTest.kt`。

1. 补双网络用例：普通/位置/聊天文字在 299999ms 不能再次传、300000ms 可以；首次空队列和输入暂停不消费下一次资格；无使用记录不消费应用使用批次。
2. 示例：首次 `plan.select(false,true,kinds,true,false)` 后，时钟推进至 300000，断言 `events && regular && location && chatText`，图片仍由其独立资格控制。
3. 运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*Collector*Test' --tests '*UsageUpload*Test' --offline --console=plain`，记录失败。
4. 对非图片调用显式使用两网 300000ms，避免改变被其他功能复用的默认参数。应用使用先确认可上传待传，再消费节流资格；保留手动立即尝试、实际回执确认及原过滤规则。
5. 增加无副作用的剩余等待查询（不调用 tryStart），空队列返回 null；合并待传类别取最早到期。测试查询不会消耗资格、切网不重置时钟、失败不会形成密集请求。
6. 审计 `DataCollector.flushNow` 中普通/文字之外的非图片入口，尤其页面/视频元数据；有独立上报周期的入口纳入同目标，图片依赖记录不能绕过图片回执。不要改图片和录音传输策略。

## 任务 4：待传驱动的熄屏后续任务与省电等待

**修改：** `MAIN/collect/UploadRecovery.kt`、`MAIN/collect/DataCollector.kt`、`MAIN/collect/CollectorFlushSignal.kt`；必要时任务 3 审计发现的非图片同步入口。
**测试：** `TEST/collect/UploadRecoveryTest.kt`、`TEST/collect/CollectorFlushSignalTest.kt`、`TEST/usage/UsageUploadRecoveryTest.kt`。

1. 补失败用例：屏幕关闭 6 秒后任务执行，普通/位置批次尚未到期且应用使用为空，仍预约剩余到期；多个类别只保留最早任务；新数据不能把更早任务不断往后推。
2. 补队列清空不续约、失败保留并按有限节奏重试、网络恢复重新检查、撤回同意停止、系统中止任务不清队列、任务完成/取消竞态用例。
3. 运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*UploadRecoveryTest' --tests '*CollectorFlushSignalTest' --offline --console=plain` 并确认失败。
4. 在已有 UploadRecovery 中统一收集各非图片 pending 的剩余等待，完成时预约最早到期。继续使用系统 JobScheduler，不增加精确闹钟、永久唤醒锁或新的常驻服务。
5. 有普通待传时按到期等待而非固定 30 秒轮询；事件、网络、输入/游戏恢复信号可提前打断。空队列保持 30 分钟兜底，图片快路径仅在原资格满足时使用。针对暂停和未知就绪状态保留有界重检，不忙循环。
6. 检查应用使用结束事件的 5 秒稳定窗口与熄屏 6 秒交接：用历史 UsageEvents 保留真实结束时间，不把上传时间计作使用时间。
7. 重跑相关测试，并做无使用记录但有位置、无位置但有普通记录、全空三类恢复验证；进程重建后不能因仅有内存状态失去恢复入口。

## 任务 5：回归、交付与真机验收清单

**文档：** 更新本计划执行证据；在 `AGENTS.md` 标注用户确认的新策略及取代关系，保留实施/验收状态区别。

1. 使用 @superpowers:verification-before-completion；运行 `git diff --check` 和 `./gradlew :yuyansdk:testOfflineDebugUnitTest --offline --console=plain`。检查测试 XML 的真实通过/失败/跳过数，不能只看进程退出。
2. 复查授权、输入/游戏避让、定位精度/漂移/速度、图片有效 Wi-Fi、失败持久队列、用户停止/权限恢复等回归。请求针对性代码审查，不扩展功能。
3. 按 `android/YuyanIme/tools/SIGNING.md` 构建 `./gradlew :app:assembleOfflineDebug --offline --console=plain -Pandroid.injected.testOnly=false`；验证真实包名、版本、签名和非 testOnly。
4. 按 AGENTS.md 将合格 APK 交付 `/mnt/e/Projects/shurufa-android/apk`，核对 SHA256 与固定原签名；仅在交付校验成功后清理本次 WSL 对应 APK 副本。不自动安装或清数据。
5. 真机安装/操作须另获当前任务授权。建议对照：移动网络熄屏出行≥20分钟、Wi-Fi 应用使用后立即熄屏≥10分钟、Wi-Fi 静置、Wi-Fi断开、断网补传、Doze限制。分别记录定位/入队/发送/接收时刻与系统调度延迟。
6. 电量验收使用同条件时段对照请求数、唤醒次数和耗电观察，不能以单元测试推断省电百分比。微信/抖音采集回归未能真机执行时明确列为未验收。
7. 最终报告区分源码完成、自动化通过、APK交付、安装和手机验收；无实际证据不声明问题已彻底解决。

## 执行记录（2026-10-15，源码及 APK 已交付，真机待验收）

- 任务 1：实现及规范/质量两阶段审查通过。首次红灯 48 项中 4 项预期失败，补网络回归后 51 项中 7 项预期失败；最终 Policy 10 + Service 48 = 58 项，失败/错误/跳过均 0。主代理独立读取 `build/unix/test-results/testOfflineDebugUnitTest` XML 核对，日志归档 `.runtime/screen-off-location/shurufa-location-{red,red2,green,final}.log`。未真机验证。
- 任务 2：实现及规范/质量两阶段审查通过。真实队列红测证明旧逻辑 5 个分钟点只保留 1 点；改后完整 Location 回归 11 类 149 项，失败/错误/跳过均 0，主代理独立核对 XML。覆盖 SQLite 写入失败后基准不推进、同点重试、关库重开保留全部点，以及异步前复制可变 Location。日志 `.runtime/screen-off-location/task2/`。不把关库重开等同于真实进程终止验收。
- 任务 3：实现及规范/质量两阶段审查通过，主代理核验归档 XML 为 29 类 213 项全部通过。统一业务非图片两网 5 分钟；新增 `CollectorUploadPlan.nextUploadDelay`、`VideoVisitSync.nextUploadDelay` 和纯资格 `CollectorTargetGate.canAttempt`，保留实际发送的健康门禁。真实 HTTP 红测曾发现未到期/失败健康探测重复请求，现以批次限频；查询无健康 HTTP。视频元数据最多 100 条、20 秒新请求发起窗口，最后一笔另有 15 秒超时，不宣称整批 20 秒硬截止。技术诊断策略保持不变，未纳入业务成功计数。日志与 XML：`.runtime/screen-off-location/task3/health-final*`。
- 构建环境：`source /home/ko/android-tools/env.sh` 后运行计划 Gradle 命令；不改 Windows 共用 `local.properties`，不打印签名配置。Gradle 输出按仓库既有规则隔离到 `build/unix`。
- 实施前上传入口审计：`PageCaptureSync` 请求包含图片，保留既有图片规则；`VideoVisitSync` 是小型结束元数据，旧实现仅 Wi-Fi，现已纳入非图片策略并保持回执与图片引用语义。`ChatCaptureDiagnostics` 是独立技术诊断，其待传状态不代表业务记录已上传。
- 实施前 `DataCollector.requestSync` 仅唤醒进程协程；任务 4 已验证实际入队交给系统任务、结束后按非图片待传续约。
- 任务 4 追加父代理审查发现：视频 20 秒预算曾混入 `current()`，会影响预算到期后的合法在途响应确认。现已将预算仅用于新请求启动，保留权限/取消/切网的回执守卫，并通过回归验证。
- 空队列续约边界：没有新事件时全空不持续续约、不发空请求。若任务运行期间真实入队/屏幕事件已留下后续唤醒，而当前批次恰好清空队列，允许一次有界补查以避免并发漏单；不为省掉这一次检查引入额外状态机，也不能将它变成连续空轮询。
- 任务 4：规范及质量审查均闭合，主代理独立读取 `final2-xml` 核对 22 类 209 项全部通过，日志 `.runtime/screen-off-location/task4/`。红测覆盖缺少普通/位置续约、真实入队交系统、到期等待、暂停元数据查询、旧 Service stop、调度抛错、查询异常恢复、usage 新结束记录/gap 交接与视频回执时间窗。修复后暂停新入队交接下限 5 分钟；新增查询故障退回 30 分钟兜底但真实信号可提前打断；协程取消继续传播。图片快路径不触发 usage 全量查询，视频预算仅约束启动。
- 任务 5：主代理已完成全量 SDK 单元测试、HEAD 失败对照、最终跨组件代码审查及原签名 APK 校验交付；未安装或进行手机操作，详见下方最终结果。
- 最终跨端审查发现并追加修复 1 项（规范复审及独立质量审查均通过）：蜂窝上的视频元数据如仍引用本地未上传首图，服务端会按既有引用门禁返回 409；普通失败退避会持续产生已知必败请求。补客户端图片依赖等待及仅等图不续约，不改后端门禁、不抹引用或伪造回执。修复前的全量测试仅作为中间基线，修复后重新全量验证。

### 最终审查追加修复：视频图片依赖等待

- `VideoVisitStore` 本地库由 v2 升至 v3，新增可空 `first_image_id`、`last_image_id` 查询列，按旧 payload 回填；原 payload、ID、退避和 active 状态不变，兼容 v1 升级链路。
- 读取本地页面待传图片 ID 的有界元数据快照，在 SQL LIMIT 之前过滤仍等待图片的访问；无图及依赖就绪记录仍按两网 5 分钟策略，不能被前排阻塞记录饿死。
- `nextUploadDelay` 只查查询列/退避，不解码全部视频 payload，避免图片快速循环或输入暂停时产生大量 CPU 开销。仅等图片返回 null；图片确认后重新查询自然恢复。
- 无跨调用缓存、不改服务端协议、不丢引用、不伪造回执。先补旧库迁移、首/尾依赖、100 条以上头部阻塞、确认后恢复及暂停查询测试，看红再最小实现，并重新审查与回归。

### 全量验证环境与既有缺口

- 第一次全量 SDK 测试默认 JVM 仅 512 MiB，运行至 1447 项时出现大量 `OutOfMemoryError`，主代理终止已污染的本轮测试 worker（退出 143）。此结果不能当作最终有效全量回归；日志 `full-tests.log`。
- 此轮内存耗尽前还暴露一项既有 UI 标题断言冲突：HEAD 中 `CallRecordingSettingsActivity` 标题已为“音频与文件”，同一 HEAD 的 `CallRecordingSettingsDisclosureTest` 仍要求“输入记忆与上传”；两个文件本轮均未修改。保留此缺口，不擅自改本任务之外的录音页面或隐藏失败。
- 最终全量已使用 `.runtime` 临时 Gradle init 配置（单 worker、1024 MiB、每 8 类重启）完成，无 OOM；未改产品构建设置，结果见下文。

- 图片依赖追加修复：定向红测 8 类 83 项中 7 项预期失败；改后 VideoVisit/PageCapture/UploadRecovery/CollectorFlushSignal 扩展回归 19 类 199 项，失败/错误/跳过均 0。主代理独立核对 XML，归档 `.runtime/screen-off-location/task3/image-dependency-{red,green}*`。v1/v2 本地 SQLite 迁移、首尾各自确认、仅等图不续约/不请求、205 条前排阻塞不饿死后排、SQL 先过滤不解码被阻 payload 均已覆盖；规范复审及独立质量审查均通过，原 important 已关闭；修复后全量与 HEAD 对照已完成，结果见下文。

### 修复后全量与旧失败对照

- 隔离 worker 全量已完整结束，无 OOM：399 类 2969 项，2963 通过、6 失败、错误/跳过 0（退出 1）。日志 `full-tests-isolated.log`，XML `full-tests-isolated-xml/`。本次相关位置、队列、批次、usage、视频依赖与恢复测试在此全量中全部通过；不能称全量全绿。
- 6 个失败类：`CallRecordingSettingsDisclosureTest`、`ExpressionPrebuiltAssetsTest`、`ExpressionManualSearchInputViewTest`、`SymbolToolbarSizeTest`、`WechatListCaptureTest`、`CandidatesBarTest`。当前源码定向复测 107 项仍为相同 6 项失败（日志 `full-failures-recheck.log` / 同名前缀 XML）。
- 初步静态归因：旧录音标题、推荐别名已扩召回、共享 Job 取代窗口直接 HTTP、图标尺寸、微信列表改独立页面采集、Drawable 类型断言，涉及代码与测试均未在本任务修改。但“未改”不单独证明无回归，随后用 `git archive HEAD` 创建的 `.runtime/screen-off-location/head-baseline/` 源码快照实测这 6 条；不是 worktree，也没有切换或覆盖当前工作区。

- HEAD 对照已完成：以 `dc5a8202247021d30e3df91df18a4226602c9e86` 的完整 Android 已跟踪源码及素材清单快照执行上述 6 条失败测试，6 条均同样失败；主代理逐条比较类名、用例名与失败 message（仅归一化动态 ClassLoader ID），完全一致。日志 `head-baseline-failures.log`，XML `head-baseline-failures-xml/`。因此确认这 6 项为既有基线缺口，本轮不扩大范围修正无关 UI/推荐/列表测试，也不声称全量通过。当前实现未在此全量中引入新增失败；原签名 APK 已构建并校验，详见交付记录。

## 最终 APK 交付与边界

- 构建命令：`source /home/ko/android-tools/env.sh` 后在 Android 根运行 `./gradlew :app:assembleOfflineDebug --offline --console=plain -Pandroid.injected.testOnly=false`，退出 0，日志 `.runtime/screen-off-location/apk-build.log`。
- 唯一推荐文件：`E:\Projects\shurufa-android\apk\shurufa-2026-10-15-v20261015.13-2026101513-debug-f2de4576.apk`（WSL `/mnt/e/Projects/shurufa-android/apk/shurufa-2026-10-15-v20261015.13-2026101513-debug-f2de4576.apk`）。
- 从实际 APK 核对：包名 `com.yuyan.pinyin.offline.debug`，versionName `20261015.13`，versionCode `2026101513`，minSdk 23、targetSdk 36，`arm64-v8a`，非 testOnly。
- SHA256：`f2de45761bbf4441e179d7c5063057f14b909dfaf2220f363b321406a485ab36`。源 APK 与 E 盘副本一致；API 23/27/28/32/36 均验证固定原证书 `a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。
- 校验成功后只删除本次对应 WSL 构建 APK `app/build/unix/outputs/apk/offline/debug/yuyanIme_2026101513_debug.apk`，复验 E 盘文件仍存在且哈希未变。记录 `.runtime/screen-off-location/apk-verification.log`。
- `git diff --check` 通过；未提交、推送、部署、安装或操作手机。未证明原截图事件具体根因、Doze 准点、真实乘车轨迹完整性或续航提升；真机需按任务 5 清单验收。权限/授权、输入/游戏优先、图片与录音策略保留，不以自动化通过代替上述验收。
