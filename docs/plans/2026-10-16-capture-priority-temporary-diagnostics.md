# 采集正确性优先与临时排障日志实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 按用户确认的规则保留移动中的分钟有效点，普通非图片数据两网五分钟按需合批，诊断默认关闭且临时开启后自动清理。

**Architecture:** 定位质量、运动证据、保存节奏与上传节奏分别判断；可信移动不再被最终距离门槛重复过滤，静止仍无重复心跳。诊断由单独的临时会话控制，业务不依赖诊断开关；现有授权、敏感页面排除、输入/游戏避让和截图仅Wi-Fi上传继续生效。

**Tech Stack:** Kotlin、LocationManager、SQLite、协程、Android Preference、Robolectric/JUnit。

## 用户已确认的统一规则

- 优先实现正确截图、正确定位、及时上报，再优化耗电。减少重复和无产出工作，不能靠漏采达成省电。
- 用户已确认：有可信证据正在移动时，每约1分钟保留合格独立新点，即使离上次保存点很近或绕回附近；确认静止仍不重复上报。不能将低精度、缓存、异常跳点或单纯晃动算作真实运动。
- 用户最新修订取代上一轮分钟上传确认：普通非图片结构化数据可汇集约5分钟合批，仅有新数据或待补传才预约任务；无人操作、没移动且队列为空时不空上报；保留30分钟资源/配置下拉兜底和待下载恢复，活跃上传批次可按既有资格合并版本检查。截图仍仅有效Wi-Fi尽快上传，流量下保存待传；录音大文件沿用原Wi-Fi规则。采样、入队和服务端确认分开统计，不将请求成功当作保存回执。
- 用户要求日志只在排障时需要：默认关闭、不自动因升级开启。临时排障默认1小时，到期停止；进程存活时清理，进程未运行时下次启动先清理。关闭需清除诊断文件与内存待写，不删除截图/位置/录音或业务待传，不重置采集预算。

## 当前确证问题

1. `LocationUploadPolicy.shouldUpload` 最终仍只按位移决定保存。`LocationJumpFilter`已有可信速度/连续定位证据，但最终门槛丢弃近距离合格移动点。非精细点阈值至少50米和双方误差和，精细点至少10米和误差和；这能造成小范围走动点稀疏，不能据此还原历史7分钟中每个回调原因。
2. 用户最新确认普通非图片数据保持300秒合批；问题在空队列仍恢复周期上传/无条件屏幕唤醒，不能因此停掉服务端下拉更新检查。
3. `CaptureHistory`在总同意后一直写，无独立排障开关。4096条/48小时是读写触发清理，SQLite文件保留，不能称自动到期删除文件。聊天12槽诊断也常驻并周期补传，须统一门控。

## 任务1：移动分钟点（定位域）

文件：`data/collect/LocationJumpFilter.kt`、`LocationUploadPolicy.kt`及同包测试；`DataCollector.kt`由根代理集成。

1. 先加红测：可信移动小于距离门槛/相同终点仍按分钟保存；无速度证据但独立定位连续确认的慢行保留；可信0速度、漂移、缓存、陈旧、跳点及重复time不借新规则入队。
2. 根代理串行运行`:yuyansdk:testOfflineDebugUnitTest --tests '*Location*'`，核对红测后实施最小运动证据传递；不得直接对所有点关闭距离判断。
3. 静止/无证据继续距离与确认策略，保留已有GPS有界生命周期；每次可信运动证据只针对当前独立观测，不靠过往状态永久放行。

## 任务2：非图片五分钟按需上传（上传域）

文件：`data/collect/ReportSyncJobService.kt`、`UploadRecovery.kt`、`BackgroundRefreshRuntime.kt`、资源同步任务、`CollectorFlushSignal.kt`及必要调度回归；`DataCollector.kt`和IME活动入口由根代理集成。

1. 保留现有两网300秒合批，红测空队列不创建周期上报任务/不消费资格、屏幕解锁不空预约、有待传一次性任务持久化、旧周期退役保留其他组件、输入暂停不消费资格。
2. 启动/网络恢复先查询原始持久待传再预约，离线结构化队列保留ANY任务、仅图片使用Wi-Fi任务，覆盖仅导航/使用/视频元数据及图片依赖。资源检查保留原30分钟持久任务、Handler恢复及网络资格，已有下载继续；真实活动和普通上传批次按原资格合并检查，不能仅因无待上传而停止下拉。空Collector协程等新事件信号，不30分钟空轮询；必要本地使用采样/低频位置监测保留，不承诺零耗电。不改截图网络边界和失败待传/精确回执。

## 任务3：临时诊断（根代理）

文件：新增`data/collect/CollectionDiagnostics.kt`，修改`CaptureHistoryStore.kt`、`CaptureTrace.kt`、`ReportingTrace.kt`、`ChatCaptureDiagnostics.kt`、`ChatDiagnosticsBuffer.kt`、`DouyinCaptureDiagnostics.kt`、`PageProbeDiagnostics.kt`、`DataCollector.kt`、`BalancedLocationService.kt`、`OtherSettingsFragment.kt`及对应测试。

1. 红测默认无诊断库/无待写、开启后关键事件可回溯、到期/手动关闭/撤权停止并清理、关闭与写入竞争不复活文件、新会话不复用旧待写/旧上传、重启过期先清理。
2. 设置中提供临时排障开关，明确1小时自动关闭和立即关闭清理；复用总授权，业务开关独立。到期清理不申请额外系统常驻、Alarm或WakeLock。
3. SQLite历史与12槽偏好/旧诊断文件统一门控与清理；相关Logcat详细trace仅排障期间输出，关日志不执行额外日志上传/格式化/数据库写入。业务截图预算、回执和待传库不在清理范围。

## 任务4：截图规则回归与交付

1. 复核既有规则：聊天进入/发送后内容变化取帧，同内容去重；支付独立预算、共享截图槽有界等待；信息流稳定3秒首图、微信接通通话5秒独立图，敏感输入排除。没有实证不猜改布局或扩大截图范围。
2. 回归连续两次发送、支付竞争、流量下本地保存、Wi-Fi恢复精确回执、日志关闭仍截图/定位/上传；重点测试失败清理，保留上一轮测试隔离限制和既有失败记录。
3. 根代理统一串行Gradle相关检查，`:app:assembleOfflineDebug -Pandroid.injected.testOnly=false --offline`，核验原签名、版本、非testOnly及源/交付SHA一致，Mac交付项目`apk/`。不自动安装、提交、推送或部署。

## 工作区与验证纪律

- 起始HEAD `114437c`，当前main工作区干净，沿当前分支开发，不建分支或worktree。
- 领域代理不并发运行Gradle、不同时修改DataCollector；根代理唯一修改共享集成点，测试准备/红测/实施/绿测依次执行。
- 本文件记录设计与计划，完成状态以实际测试、APK及真机证据更新，不承诺系统无有效fix时准点造点或未知页面100%截图。

## 本轮完成与验证（2026-10-16）

- 已完成可信移动分钟保留与DataCollector桥接；连续运动只用已接受点，不借来源保护拒绝的network点。新增近距离、绕回、无速度连续观测、落盘失败重试与来源回归均由红到绿。
- 普通两网5分钟按需持久恢复，空Collector等信号；仅图片UNMETERED，独立文字ANY，依赖图片的文字不空占普通批次。离线usage/video持久查询保留权限、图片依赖和退避；输入/游戏暂停至少5分钟恢复，页面图按retry_after/平台资格恢复。保留30分钟资源周期与待下载恢复，并在有普通业务批次时沿原due合并资源request。
- 临时诊断默认关闭，1小时双时钟/启动标识会话，到期、关闭、撤权停止并清理；关闭清理不受离开设置页面取消影响，旧session不能写入新session。抖音诊断保留apply即时内存顺序，关闭时commit等此前apply后删文件。
- 已知磁盘遗留崩溃日志30天清理，启动及Expression周期IO顺带执行、每日一次、不递归/不跟随符号链接。临时采集诊断更早清理，不需要留满30天。
- 核心回归363/363通过；截图/历史回归原166项中的3项默认日志fixture适配后，最终16项定向复验全过。按(classname,name)合并最终结果，共527个相关案例通过，无跳过。XML证据在`.runtime/capture-priority-20261016/{final-core-results,capture-results,final-capture-results}/`；日志对应`core-final-green.log`、`capture-regression.log`、`capture-final-green.log`。
- 扩展Expression全组曾触发Robolectric worker内存不足，不能称全库全绿。单独重跑ExpressionDeliveryPolicyTest与ExpressionPrebuiltAssetsTest共13项，前者通过，后者仍有1项目录断言失败：新五词资源预期manifest推荐ID顺序不一致（line77）；资源与该测试本轮未改。记录在`existing-expression-check.log`，未以改变目录掩盖。本轮没有安装手机或验收真实骑行/续航，也没有部署后台/提交推送Git。
- `:app:assembleOfflineDebug -Pandroid.injected.testOnly=false --offline`成功。交付`apk/shurufa-2026-10-16-v20261016.22-2026101622-debug-fe18a6a1.apk`，versionName20261016.22、versionCode2026101622，189419584字节，源/交付SHA256一致：`fe18a6a1f715280bd92e4d73f79e262f223d54b8a6576201142113008b974e19`。API23/27/28/32/36固定原签名核验通过，非testOnly；不是已安装或真机验收证明。
