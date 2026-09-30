# 输入优先与候选状态修复实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 先消除已确认的候选覆盖、生命周期崩溃和后台避让缺口，再安全拆分同步持久化。
**架构：** 保留现有候选引擎、WAL 与数据协议；入口和引擎两层保护组合态。复用现有 ImageUploadRuntime 忙闲状态，热路径只标记取消代次，后台负责取消网络请求。后台工作在阶段边界避让，不承诺强制打断已提交的系统截图或原生编码。
**技术栈：** Kotlin、Android、JUnit/Robolectric、Gradle。

## 方案与范围

采用最小分批修复，不关闭采集、不清学习、不重构整套引擎。不采用仅延长截图间隔（遗漏其他入口），也不无差别删除数据库锁（现有 helper 已隔离）。用户“继续”确认上轮修复方向；本轮先完成第一批并报告检查点。异步事件/学习写入须另外明确接收、持久化、失败与进程终止语义，不能把排队返回当落盘成功。

## 第一批

### 1. 迟到回调不覆盖组合态
- 修改 `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/inputmethod/RimeEngine.kt`、`core/Kernel.kt`（同目录）、`com/yuyan/imemodule/service/DecodingInfo.kt`、`keyboard/InputView.kt`（后两者均位于同一 src/main/java）。
- 测试 `android/YuyanIme/yuyansdk/src/test/java/com/yuyan/inputmethod/T9FullCompositionDisplayTest.kt`：构造真实 6243 keyRecordStack、候选和 metadata，触发联想入口，要求 composition、候选、nativeIndex 与 isAssociate 不变。覆盖空上下文与正常文字，锁音/分段状态也不能被清除。
- 先运行失败测试；最小实现 activeComposition 门禁，在显示重置前及引擎写状态前检查；回归现有显示、分段及索引测试。

### 2. 生命周期空状态
- 修改 `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/ImeService.kt`。
- 新增同模块 `src/test/java/com/yuyan/imemodule/service/ImeLifecycleGuardTest.kt`，校验 updateSelection 未初始化视图的保护以及 requestCursorUpdates 可空连接调用。静态接线测试不冒充真机生命周期验收。
- 先失败再补最小空值/初始化保护，检查正常回调仍转发。

### 3. 输入忙闲与后台避让
- 修改 `data/collect/ImageUploadRuntime.kt`、`DataCollector.kt`、`service/capture/PassiveChatAccessibilityService.kt`、`data/capture/media/WindowScreenshotter.kt`、`WechatScreenshotIdentity.kt`，路径均位于模块 src/main/java/com/yuyan/imemodule。
- 新建可测试的合并后台取消器，输入线程只递增代次和投递一个取消任务；后台仅取消早于本次输入的请求，不能误伤排队后新创建的请求。
- 普通上报、词库同步、页面树读取及截图像素复制/OCR开始前检查忙闲；不阻塞主线程等待空闲，不读取打字时的新页面。保留必要持久队列和后续重试。
- 单测覆盖取消合并、再次输入、拒绝执行、忙闲门禁；源码接线验证覆盖实际入口。先失败，再实现，再回归现有采集与上报测试。

## 第二批（第一批检查点后继续）

- 将事件记录与选词学习写入改为有界有序后台持久化，设计未持久化状态及失败回传；保持隐私和纠错学习语义。
- 补内容无关、有限采样的输入耗时/长帧诊断，避免日志本身干扰输入。
- 真机并发压力验收由用户操作，助手只读记录。

## 命令与交付

在 `android/YuyanIme` 中 source /home/ko/android-tools/env.sh 后运行：
`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*T9FullCompositionDisplayTest' --tests '*ImeLifecycleGuardTest' --tests '*InputPriority*Test'`，RED 预期行为断言失败，GREEN 预期全部通过。
再运行关联采集、上报、显示与学习回归，`git diff --check`。
通过后 `./gradlew :app:assembleOfflineDebug`，使用 tools/verify-delivery-apk.py 核对原签名、非 testOnly、版本及 E 盘复制 SHA256。不自动安装、提交或推送。

## 第一批实现记录

- 组合态在 InputView、DecodingInfo、RimeEngine 三处受保护；键栈含锁音/分段动作、显示组合或原生组合任一活跃即不改为联想。保留候选元数据与原生选择索引。
- ImeService 配置回调允许空输入连接，光标回调允许视图尚未初始化。
- 新增 InputPriorityCancellation：触摸线程只更新代次/合并调度，后台关闭旧网络请求，旧任务不会取消新代次请求。
- 新增 LatestIdleWork，分别接入原始事件、稳定视口、前台探测、空树截图、通知补偿五个入口；忙时仅保留最新等待者，已开始的工作不被后续探测取消。重置身份取消等待，后续仍校验窗口/会话。
- 通知补偿的页面树读取不再回到主线程。系统截图提交前、像素复制前及 OCR 阶段检查忙闲。普通上传按批/请求检查；词库同步入口、注册返回后导出前后、摘要及逐批序列化前复核空闲。已确认上传的数据仍可正常确认，不删除未发送队列。
- 词库同步被输入打断时不标记常规同步完成。

### 红绿与审查记录

- 第一轮实际行为/接线回归 16 项中 9 项按预期失败：候选元数据与联想状态、生命周期和忙闲接线。
- 新的取消器、等待合并器先编译失败（尚无实现），实现后运行各 5 项、4 项行为测试。
- 词库网络响应后恢复输入的回归，先修正 API28 测试夹具的 AutoCloseable 兼容问题，再观察到 pending_learning 预期保留 1 行、实际被提前结算为 0 的断言失败，随后补阶段门禁。
- 代码审查指出等待者可能积压和注册返回后继续全量导出两项问题，均已处理；复核补充通知补偿第五入口，也已接入合并器。
- 定向复验 42 项通过；最终扩大回归 690 项、119 个测试类，0 失败/错误/跳过。范围为 collect、capture、service.capture、inputmethod、生命周期及纠错学习/候选索引相关测试，不声称全仓全部单测通过。
- 首次广泛回归受默认测试堆内存不足影响而中断，不计通过。截图线程测试跨 Robolectric 时钟重置受运行时单例影响，沿用已有 ScreenshotWindowReadThreadTest 的显式空闲夹具；隔离两项截图线程测试通过。最终复跑使用本机临时 Gradle init 脚本给测试 JVM 1536m、单进程并每 40 个测试类换进程，不修改项目构建规则。

### 未完成与不可承诺

- 第一批结束时，事件记录、选词学习仍有同步 SQLite 写入。第二批后续状态见下方检查点记录；不得宣称整个卡顿问题已解决。
- 已提交的系统截图、已经开始的 native 编码/OCR/SQLite 事务不能保证即时抢占。此批做阶段边界避让，不保证任意机型零卡顿。
- 现有 ImeLatency 仅记录超过 32ms 的原生输入/候选阶段耗时，不含文字；本轮没有新增持久性能日志。之前取得的日志里没有该标签记录，也没有昨天现场，不可反推从未卡顿。
- 尚未安装新包，也未由用户进行快速打字、删除、切换输入框、截图/积压上报并发的真机验收。

## 第一批最终验证与交付

- 2026-09-30：最终 Gradle 关联回归与 `:app:assembleOfflineDebug` 同一次命令成功，耗时 5m12s；最终五个等待入口均包含在此构建中。
- 最终代码审查复核五个入口及词库阶段门禁，无剩余阻断项；审查不是运行时性能证明。
- APK：`E:\Projects\shurufa-android\apk\shurufa-2026-09-30-v20260930.09-2026093009-debug-e92d6f84.apk`。
- 包名 `com.yuyan.pinyin.offline.debug`，版本 `20260930.09` / `2026093009`；从 APK manifest 与 output-metadata 双重核对。
- SHA256：`e92d6f84e54157f270826cbe58fd4f72d745ba2951862725ef20bfcac4ca14f1`；源 APK 与 E 盘交付文件一致。大小 140054368 字节。
- 原证书校验 API23/27/28/32/36 全部通过，非 testOnly，`git diff --check` 通过。编译仍有原有 deprecated / Kotlin 类型推断告警；不称零告警。
- 未安装手机，未提交或推送。第一批结束保留当前工作区，第二批尚未完成。
- 用户随后明确选择方案 1：打字时内存暂存，停手后有序落盘；接受进程意外终止丢失尚未落盘记录。既有持久数据不动，内存接收不称保存成功。


## 第二批执行计划（用户方案 1）

本批按 writing-plans / executing-plans 分检查点执行，不自动提交或安装。

### 检查点 A：事件写入避让
1. 新增 `android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/collect/IdlePersistenceQueueTest.kt`：先验证持续输入不调用写入、FIFO、恢复输入暂停后续项、失败保留队首并重试、数量/内存上限及非阻塞接收。运行同名 Gradle 测试，确认 RED。
2. 新增对应 main 目录 `IdlePersistenceQueue.kt`：单工作协程、有界内存 FIFO；只在空闲逐项执行，失败延迟重试，锁内不做数据库/网络操作。不取消已开始事务，输入恢复后不启动下一项。
3. 修改 `DataCollector.recordEvent`：输入侧只校验和暂存原始事件，设备身份文件、App 名查询、日期格式化、SQLite 移至空闲消费者。返回值明确仅代表内存接收；队满拒绝，不同步兜底、不覆盖已接收项。固定事件 ID 保证重试幂等。更新 `CollectorPrivacyIntegrationTest`，补真实入口忙时零落盘、事件顺序、撤权后不补存（含关闭后重新开启）。执行 RED → GREEN。
4. 相关 collect 回归、diff 检查；检查点报告实际结果。

### 检查点 B：学习记录
保留即时排序与严格纠错语义；先验证临时学习/撤销/保留片段顺序及原始选择时间，再实现内存学习与空闲持久化。不得仅把旧方法放 IO 协程导致输入期间争写锁，也不得以等待落盘替代即时本机学习。此项尚未实现，不随 A 的通过宣称完成。

### 检查点 C：验收交付
关联回归、代码审查、原签名 APK 固定目录交付；真机性能由用户操作，助手只读采样。未运行的真机验收明确列出。

### 学习检查点的审查约束（尚未实施）
- 独立只读审查建议：可纠错奖励在原始 17 秒观察窗内只保留内存；普通学习与可纠错学习共用入口，上传权限/目标在选择时捕获。到期且输入空闲后才冻结单笔/批次，以原始选择时间写入。
- 不可仅“提交 DB → 删除内存”：并发候选查询可能重复或漏算。拟使用同事务的幂等学习收据，并在同一条只读 SELECT 中读取正式证据和相关收据，再决定是否叠加捕获的内存快照；不用争抢写锁的读取事务。
- 收据表及候选读路径属于新的迁移/并发验证范围，不能把事件队列直接套上旧 learn 方法即宣称已完成；先测提交前后屏障、提交后回调失败重试、部分纠错与冻结竞争、禁词和候选排序。
- 此段为待实施技术方案，不代表用户确认了新的产品行为或学习功能已经异步化。既有词库和旧 pending_learning 恢复路径须保留。


### 检查点 A 实现记录
- 新增 IdlePersistenceQueue：最多 256 项、估算内存预算 4 MiB（包含在途项），FIFO 单消费者；锁内仅维护队列，空闲逐项落盘，异常保留队首延迟重试。满额返回 false，不挤掉旧项、不同步兜底。此为易失缓冲，不是持久队列。
- DataCollector.recordEvent 不再在输入回调查设备身份文件、App 名、版本或写 SQLite；原始时间、序号、会话、目标与隐私资格在接收时固定。诊断元数据作限额深拷贝（64 KiB 估算、最多 16 层），不在输入侧序列化。
- 候选诊断的 app_version 改为空闲消费者补充。输入事件 network_type 留空，不把延迟持久化时的网络状态冒充发生时状态；其他上报类型未因此更改。
- CollectionConsent 增加进程内授权代次，关闭后重开仍丢弃旧未保存事件，但不删除已有持久记录。后台身份/应用名查询返回后再次等待空闲，再复核授权后写入。
- 组件和接入两轮只读审查未发现阻断问题。使用进程级 scope，输入恢复以挂起表达，不能通过取消 worker 暂停；写入闭包为同 ID 幂等事务，提交后唤醒补传失败不反向重试事务。
- RED：新类缺失的编译失败；队列实现后 5 项行为通过。真实入口三项用例在旧代码上分别因忙时已写库、撤权重开保留旧记录失败。最初多用例共享旧 helper 的夹具问题已纠正，不把该错误算作业务 RED。
- 首次 GREEN 25 项中 24 项通过；一项测试两个 helper 同时首次建空库触发 pending_report 已存在，夹具改为与正常输入启动一致的预先打开数据库，再复跑。尚不能据此声称冷启动并发建库已独立验收。
- 新增在途写入期间仍可接收/仍占预算验证，以及原始时间不会被停手后的时间替代的验证。扩大回归 collect 全组 + ImeServiceCommittedEditTest + ImeLifecycleGuardTest：50 个测试类、267 项、0 失败/错误/跳过，Gradle 成功（4m2s）。随后追加元数据深拷贝/超额拒绝保护用例，定向验证与阶段打包结果见下。

### 检查点 A 最终定向验证与阶段交付
- 最终队列 / 隐私入口 / IME 提交链路定向复验：3 类、27 项，0 失败/错误/跳过；包括诊断元数据嵌套可变对象入队后被修改仍保留原值、超额诊断明确返回 false。与上述 267 项有重叠，不相加冒充独立用例数。
- 同次 `:app:assembleOfflineDebug` 成功（3m7s），阶段包：`E:\Projects\shurufa-android\apk\shurufa-2026-09-30-v20260930.10-2026093010-debug-bd71b5f7.apk`。这是本检查点推荐包，第一批 `.09` 为历史交付。
- 包名 `com.yuyan.pinyin.offline.debug`；manifest 与 output-metadata 核对版本 `20260930.10` / `2026093010`。文件大小 140062560 字节。
- 源文件与交付文件 SHA256 一致：`bd71b5f709c2142c791d51dd379e6e05d8fe0e2c247e5496e37f8cdb9ebd2560`。API23/27/28/32/36 均为固定原证书，非 testOnly；`git diff --check` 通过。
- 未安装、未提交、未推送。其他并行会话产生的 client / 表情导入改动未触碰，不包含在本检查点完成声明中。
- 本检查点只完成事件采集写入避让；学习记录仍有同步写入，学习内存层/收据迁移、输入延迟诊断与荣耀真机并发流畅性验收未完成。不得将阶段包称作全部卡顿问题已修复。
