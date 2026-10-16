# 定位产出与微信漏截图诊断实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修复定位筛选吞点及无产出GPS持续运行，补全聊天阶段持久诊断，交付原签名Mac APK。

**Architecture:** 沿用系统定位及现有质量门槛，将网络监听与有界GPS监听分开；回调的独立性、缓存证据与取点时间分别处理。复用本地48小时、4096条有界诊断库，不记录聊天正文、联系人、金额或坐标。地图用独立虚线表达近距离缺口，缺口不参与里程、速度或停留推断。

**Tech Stack:** Kotlin / Android LocationManager / SQLite / Robolectric / Vue / Leaflet / Vitest。

## 已确认事实及边界

- 手机安装20261015.23；上午20261016.10支付竞争修复及持久日志尚未安装。本地与线上下午九个定位点一致，不能归因为上传漏点。
- 系统30/60秒请求已存在，下午41分03秒中全机GPS活跃36分47秒；不能将单次1%掉电全归输入法。
- 可复现已处理的旧高精度重复候选压住独立新点；静止与确认过期策略、GPS无产出缺少退出机制需分别回归。
- 微信19:12后几秒的新消息与近期微信支付，旧12槽诊断已被后来事件覆盖；不拿19:39取消解释19:12，先补可回溯证据，沿用上午已完成的支付锁等待修复。

## 任务1：定位（当前分支）

文件：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/collect/`定位类及同包测试。

1. 写可复现红测：旧重复择优吞新点、明确缓存不确立静止、确认超时可重新进入有界确认。
2. 根会话串行运行`:yuyansdk:testDebugUnitTest --tests '*Location*'`，核对失败原因后最小修改。
3. 移动采样和入队目标60秒；静止保留网络/passive低频检查并退出GPS。启动/疑似运动GPS窗口120秒，无独立合格点超时退出，60/120/300秒退避；用elapsedRealtime计时，停服务、撤权、旧回调不复活监听。
4. 回归无fix必停、静止不永久GPS、持续合格骑行点保留全部、真实90秒冷启动可确认、不松跳点/精度门槛。120秒和退避是工程预算，真机效果另验。

审查补充：疑似移动确认窗口统一120秒，确认冷却单独60秒；连续无独立合格GPS产出的失败周期，仅允许一次可信新运动提前重试，不重置失败阶梯。明确缓存不能参与实时择优、裁剪或容量竞争，也不能入队/推进位置基准。原接收时刻的单调时钟大于0时校验60秒新鲜度，0按旧格式兼容。SDK31以上原生每次请求期限不超过120秒，择优已有5秒余量不随任意处理延后增长；SDK23至30深睡不能保证严格物理退出，不引入永久唤醒锁。

## 任务2：聊天持久阶段

文件：`CaptureTrace.kt`、`ChatCaptureDiagnostics.kt`、无障碍服务绑定、`CaptureHistoryStore.kt`及对应测试。

1. 固定枚举映射先写红测，记录发送/渲染/快帧许可、取帧/取消、身份拒绝、正文重复、保存结果；避免高频树事件耗尽容量。
2. 复用已有授权epoch和输入/游戏后台门禁。无自由文本字段、不增加schema、不上传诊断，不猜改微信布局或放宽支付敏感页。
3. 串行运行截图、支付、持久诊断回归；上午支付独立锁等待修复须保留。

## 任务3：地图近距离缺口

文件：`client/src/locationAnalysis.ts`、`client/src/views/LocationTrack.vue`及分析/浏览器测试。

1. 测试同设备同北京日、15至30分钟、端点相距不超过300米才产生单独缺口对象；不同设备/跨日/无效点/大范围不连接。
2. 连续段及原里程/速度/停留计算保持边界，缺口以虚线和“期间缺少定位点，路线未知”展示，不画方向箭头。
3. Vitest、客户端构建及实际Leaflet浏览器检查。用户尚未选择虚线方案时按推荐方案实现可review结果，不声称线上生效。

## 任务4：验证与交付

1. 根会话串行Android相关测试、`:app:assembleOfflineDebug -Pandroid.injected.testOnly=false`，保留独立日志与XML。
2. 核验版本、非testOnly、固定原证书、SHA一致，将包交付项目`apk/`。不自动安装、提交、推送或上线。
3. 写明验证结果与真机待验收：移动有效点间隔、静止GPS退出、微信连续两条发送和支付图片落盘/回执，不能把单测等同真实续航及采集覆盖率。

## 已发现的验证基线限制

- 首轮完整相关集1099项中8项失败：7项是GPS监听拆分/预算计时引起的旧测试假设，已修正夹具；另1项 `WechatListCaptureTest.rawPixelsDeduplicateTreeOcrAndNotificationEntriesButKeepChangedRows` 在第123行expected1/was0，与10月13日独立复测的失败及堆栈完全相同。本轮不借诊断修改扩大修复旧微信列表测试。
- 最终必要集只排除此一既有失败，须分别披露最终通过数和这项既有失败，不能声称整个测试库全绿。原始失败证据位于本地 `.runtime/location-gap-20261016/first-green-results/`；历史对照 `.runtime/input-quality-20261013/final-isolated-results/`。
- 真机后续断开ADB，微信旧诊断显示8.0.79/3200；抖音诊断包含40.6.0与40.7.0，不能仅据历史槽位声称当前抖音版本。未安装新包、未进行本次两款宿主端到端验收。

## 最终验证与Mac交付

- 定位相关231项：全部通过，含cache初始/实时/容量/裁剪/持久化、duplicate重试、90秒冷启动、退避提前唤醒、静止GPS退出、撤权/旧任务清理、分钟点五分钟批次保留。
- 最终批量必要集1108项中25项失败：首个`ChatContentDedupCaptureTest`断言早于异步完成，后续24项为Robolectric `ShadowAccessibilityNodeInfo`的SparseArray初始化/清理异常。源码保持不变，在新Test worker中对涉及的5类共27项重新执行全部通过。保留批量失败和定向复验两份证据，不声称原批量任务零失败；测试等待/清理的隔离问题仍待单独整理。
- 该批量运行已显式排除前述1项10月13日既有微信列表失败，未用隐藏排除或删除测试包装全库通过。
- 地图分析23项通过，客户端构建通过，真实Leaflet桌面1280与移动390浏览器通过，包含虚线属性、缺口提示、连续路线/方向不受缺口影响及设备切换清理。仅本地代码验证，后台尚未部署。
- `:app:assembleOfflineDebug -Pandroid.injected.testOnly=false --offline`成功。交付包：`/Users/pj/project/shurufa/apk/shurufa-2026-10-16-v20261016.20-2026101620-debug-ccf659c8.apk`。
- APK真实版本20261016.20 / 2026101620，189403200字节；源输出与交付SHA256一致：`ccf659c85b15d85c6e6e986cda272f4e93b9254b6d6d33f9cc42a1fe148f639a`。API23/27/28/32/36原证书验签通过、非testOnly。
- 包包含上午已提交的支付截图锁竞争修复和本轮聊天持久阶段诊断；本次约19:12及近期微信支付的旧日志无法恢复，不能把本包构建成功当成漏图根因已确诊或真机验收。未安装手机，实际出行间隔、续航、微信/抖音端到端与支付截图待用户后续验收。
- 证据：`.runtime/location-gap-20261016/final-failed-results/`、`capture-isolated-results/`、`capture-isolated.log`、`assemble-final.log`、`apk-verification.json`、`map-green.log`、`map-browser/result.json`，诊断数据和APK不加入Git。
- 交付前ADB重新连接后只读复核：系统Android16，微信8.0.79/3200、抖音40.7.0/400701；输入法已由外部于19:52:29更新为20261016.19/2026101619。本助手未安装，该版本不是最终20261016.20；18点出行及约19:12漏图发生在该次更新之前，不能用最终读取版本覆盖早前证据。
