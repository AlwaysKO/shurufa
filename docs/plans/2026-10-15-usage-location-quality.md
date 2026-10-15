# 手机使用时间、定位质量与地图交互实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 真实展示最近使用证据，改善短距离轨迹和速度/高度有效信息，并让地图容易选点且按定位时间连线。

**架构：** 复用 UsageEvents、原生定位、持久队列和五分钟恢复批次。服务端独立存储有来源的时间快照，前端用真实定位点做明确标注的区间估算，不修改原始速度或补造历史。

**技术栈：** Kotlin / Android LocationManager / UsageStatsManager / SQLite，Express / PostgreSQL，Vue / Leaflet，Robolectric / Vitest / 浏览器夹具。

---

## 执行约束与基线

- 项目根 `/home/ko/project/shurufa`，在当前分支执行，不建立 worktree。已检查 client/server/android，无新增子目录 AGENTS；保留根规则以及当前用户修订。
- 先读同名 `-design.md`。本轮前存在上一轮未提交修改，不能 checkout/reset 覆盖；先归档 diff 与文件清单，审查时区分增量。无自动提交、推送、部署、手机连接或安装授权。
- 逐任务使用 @superpowers:test-driven-development：新增失败用例 → 运行并确认因目标行为失败 → 最小实现 → 通过 → @superpowers:requesting-code-review。不得用扩大超时或删除断言掩盖缺陷。
- 代码中确定有固定门槛和精度字段丢弃；用户历史 GPS 缺失根因未知，不以静态检查代替真机结论。
- Android 命令先 `source /home/ko/android-tools/env.sh`，在 `android/YuyanIme` 执行，不打印 env 中的私有签名信息，不改共用 local.properties。Gradle 单任务独占，XML 每批归档。
- 日志和截图只存 `.runtime/usage-location-quality/`。客户端测试由根目录 `./server/node_modules/.bin/vitest run client/tests/...` 执行；服务端测试同样可在根目录用准确路径执行。

## 任务 1：时间快照契约与服务端兼容

**文件：**
- 新增 `server/migrations/050_device_usage_evidence.sql`（实施前确认编号仍空闲）。
- 新增 `server/src/lib/deviceUsageEvidence.ts` 与对应 `.test.ts`。
- 修改 `server/src/api/appUsage.ts`、`server/src/api/mobile.ts`、`server/src/api/dashboard.ts`。
- 修改 `server/src/types/events.ts`、`client/src/api/index.ts`。
- 测试 `server/src/api/deviceInteraction.postgres.test.ts`、`appUsage.postgres.test.ts`、`deviceDirectory.test.ts`；新增 `deviceUsageEvidence.postgres.test.ts`。

1. 固定可选快照字段：分类原始事件时间/来源及批次版本；原有人为交互字段不改成应用活动。SQL 允许空值，分别保存事件时间和接收时间，按类别单调更新。
2. 先补测试：10:10 原始 off 与10:08 pausedAt不同；10:45补传仍显示10:10，不倒退；旧使用段没有快照也能正常保存；坏时间/未来时间/未知来源/跨设备拒绝；旧历史不伪造熄屏。
3. 运行 `./server/node_modules/.bin/vitest run server/src/lib/deviceUsageEvidence.test.ts server/src/api/deviceDirectory.test.ts`，确认失败。
4. 实现最小解析和事务更新。应用使用批次允许“记录+快照”及“仅新快照”，两者都空仍不发送。精确返回快照确认，不能把旧服务器只确认 records 的响应当成新快照已保存。
5. 遵守保存开关、保留期删除及设备隔离；重试幂等、乱序不倒退。目录返回分开的证据与主时间来源，不以 last_seen_at 充当使用时间。
6. 阅读现有 Postgres 测试环境约定后运行真实数据库测试；先确认 host/database 为本地，再只应用本轮迁移并核对字段、索引与接口。禁止直接跑可能附带素材导入的整库 migrate 命令而不审查副作用。

## 任务 2：Android 提取原始事件并随批次同步

**文件：**
- 修改 `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/usage/AppUsageTracker.kt`、`UsageStore.kt`。
- 必要时新增同目录 `UsageEvidence.kt`，不改 `UsageSessionEngine` 的原使用时长含义。
- 复用 `data/collect/HumanInteractionRuntime.kt`、`UploadRecovery.kt` 的既有接口；只在必要处改动。
- 新增对应 `UsageEvidenceTest.kt` / `UsageEvidenceUploadTest.kt`，扩展 `UsageUploadRecoveryTest.kt`。

1. RED：真实 UsageEvents 先 pause=10:08、后 off=10:10，使用段仍结束10:08，独立熄屏证据必须为10:10；锁屏独立；空轮询不能推进时间。
2. RED：没有可上报使用段但有新 off 仍保持持久待传并按五分钟交系统；重启、网络失败、旧ACK、新快照并发、权限撤销均不得丢证据或错误确认。
3. 命令 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*UsageEvidence*Test' --tests '*UsageUploadRecoveryTest' --offline --console=plain`。
4. 在原授权查询窗口提取事件并与游标推进一起落盘，避免游标已推进但证据未保存。保留30秒采样/5秒settling，不新增传感器或高频扫描；不要采集授权前历史。
5. 使用目标级确认，随批次附带已记录交互补充及分类快照；暂停期间轻量查询，不解码全部积压。快照持续不变时不发空请求。
6. 完成后回归 `*Usage*Test`、`*HumanInteraction*Test`、`*UploadRecoveryTest`，归档 XML 后再进入下一批。

## 任务 3：原生取点择优与精度感知短距移动

**文件：**
- 修改 `data/collect/LocationUploadPolicy.kt`、`LocationJumpFilter.kt`、`BalancedLocationPolicy.kt`、`BalancedLocationService.kt`、`DataCollector.kt`。
- 必要时新增同目录 `LocationFixSelection.kt`（仅用于明确的有界择优，不做泛化框架）。
- 测试同名 Policy/Service/JumpFilter 测试、`LocationStationaryFilterTest.kt`、`LocationBatchRetentionTest.kt`；新增 `LocationFixSelectionTest.kt`。

1. RED：构造 accuracy=5m、间隔60秒、移动约20m的连续GPS步行点，必须不再被固定50m全部吞掉；同样位置抖动2–5m不产生轨迹；粗糙网络点的80–120m往返漂移继续受控。
2. RED：network 先到而GPS稍后到、更差来源先到而好来源迟到、过时GPS、同时间不同来源、重复回调、窗口内无GPS、写盘失败后重试。明确观察当前实现到底在哪道门丢点。
3. 运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*Location*Test' --offline --console=plain`，保留失败证据；不得只调旧断言。
4. 从失败证据选最小择优修复。有界短窗若必要，使用单个可取消截止任务，连续回调不延长；没有更好GPS时保留合法网络点，不永久等待。不得把所有点先落盘再删除冒充择优。
5. 统一精度感知门槛在入队、运动确认与Wi-Fi降频中的语义；候选下限10m只用于高质量点，保留误差和连续证据要求。不得简单全局把50替换为10。
6. 复验拿放/摔落模拟噪声、高速度异常、0速度、静止锚点、GPS/network交替、同车Wi-Fi、窗口停止/撤权/重复start、非Wi-Fi分钟目标和五分钟批次所有点保留。
7. 此任务不保证真实GPS可用；无真机时只报告自动化和策略变化，不宣称已证明截图原因或导航级精度。

## 任务 4：保留高度证据并兼容严格接口

**文件：**
- 修改 `server/src/lib/locationContext.ts`、`server/src/api/mobileReports.test.ts`。
- 修改 Android `data/collect/LocationContextSnapshot.kt` 及 `LocationContextSnapshotTest.kt`；必要时 `LocationSpeedQuality.kt` 与同名测试。
- 修改 `client/src/api/index.ts`、`client/src/locationAnalysis.ts`、`client/tests/location-analysis.test.ts`。

1. RED：新增可选 raw_altitude_m / vertical_accuracy_m / altitude质量原因的合理、缺失、非法值测试，旧context仍合法。最终字段名保持三端一致，不让新增字段被现有严格白名单拒收。
2. 先实现服务端兼容再改Android发送。新Android向未升级服务端发送需能力门禁或明确发布先后；禁止靠丢弃整个context重试掩盖兼容问题。
3. RED：有高度但缺精度时保留原值作为参考、不作可信；可信0米不可当缺失；NaN/无限/越界不发送；无高度仍未知。
4. 实现原始值与可信值分离，类型明确WGS84椭球高，展示实际垂直精度而非永远“未记录”。不凭空转海拔、不推算楼层或摔落距离。
5. 运行 `./server/node_modules/.bin/vitest run server/src/api/mobileReports.test.ts client/tests/location-analysis.test.ts`，Android运行 `--tests '*LocationContextSnapshotTest' --tests '*LocationSpeedQualityTest'`，分别归档。

## 任务 5：区间估算、时间目录与地图交互

**文件：**
- 修改 `client/src/locationAnalysis.ts`、`client/src/views/LocationTrack.vue`、`client/src/App.vue`、`client/src/api/index.ts`。
- 修改 `client/tests/location-analysis.test.ts`、`location-track.test.ts` 及现有目录测试。
- 新增 `client/tests/usage-evidence.test.ts`、`location-track.browser.cjs`，复用现有浏览器夹具方式。

1. RED：乱序输入得到按设备与occurred_at排序的相同路线/起终点；相同时间稳定排序；断档与跨设备不连；不从接收时间排序。
2. RED：有可信实测速度（含0）不被估算覆盖；两个合格点相隔60秒、直线位移120m，得到标注区间的7.2km/h；误差过大、异常跳点、相同时间、跨设备、断档都不估算。
3. 示例断言（复用现有 point helper 后调整字段）：
   ```ts
   expect(estimate.kind).toBe('interval');
   expect(estimate.kmh).toBeCloseTo(7.2, 1);
   expect(estimate.from).toBe(first.occurred_at);
   expect(estimate.to).toBe(second.occurred_at);
   ```
4. 实现纯显示推导，不写回服务器速度，不推断交通方式。明确所估为两定位点间的区间量，非沿路里程或瞬时速度。
5. RED：目录主时间有来源，缺证据未知；off原始时间独立；延迟补传/未来数据不能伪造现在使用；不影响移动端布局或已有分页。
6. 地图视觉点与命中区域分离；重叠选择列表按时间，选中高亮。路线和起终点共用排序，方向标记不截获点点击；自动刷新保留视角与合法选中ID，移除记录后清理选择。
7. 命令 `./server/node_modules/.bin/vitest run client/tests/location-analysis.test.ts client/tests/location-track.test.ts client/tests/usage-evidence.test.ts`；真实浏览器验证390/1280px、鼠标/触屏、重叠点、缩放、刷新、HTML转义与销毁清理。source字符串断言不能替代点击行为。

## 任务 6：审查、回归与原签名交付

1. 使用 @superpowers:verification-before-completion，跑客户端/服务端全量、`npm --prefix client run build`、`npm --prefix server run build`、`git diff --check`。数据库测试记录真实连接的本地库与迁移结果，不输出凭据。
2. Android全量采用上一轮已验证的临时init：单worker、1024MiB、forkEvery=8，命令 `./gradlew :yuyansdk:testOfflineDebugUnitTest --offline --console=plain -I /home/ko/project/shurufa/.runtime/screen-off-location/isolated-tests.init.gradle`。检查XML，区分基线六失败与新增回归。
3. 请求跨端代码审查，重点是事件语义、ACK竞态、权限和网络门禁、良好GPS被吞、短距离与静止平衡、字段兼容及地图重叠点击。
4. 原签名构建 `./gradlew :app:assembleOfflineDebug --offline --console=plain -Pandroid.injected.testOnly=false`，验证实际版本、包名、SHA256与API23/27/28/32/36原签名、非testOnly。
5. 交付Windows E盘固定目录，源/目标一致后仅删除本次对应WSL APK。未批准不得部署服务端或安装手机；若新字段依赖后台升级，必须在交付说明醒目标出，不把本地兼容测试称为线上已兼容。
6. 真机待验收：同路线步行、静置、拿放、移动网络熄屏、Wi-Fi切网、应用使用后熄屏，比较来源/精度/有效速度比例/轨迹/到达延迟与耗电。不能用模拟轨迹得出真实误差或省电百分比。

## 执行记录

- 2026-10-15：完成现行代码静态调查及设计/计划；未修改本轮生产代码，未操作生产数据库或手机。用户已选择当前会话子代理驱动；实现按任务顺序执行，两阶段审查。
- 本轮基线命令：`./server/node_modules/.bin/vitest run client/tests/location-analysis.test.ts client/tests/location-track.test.ts server/src/api/mobileReports.test.ts server/src/api/deviceDirectory.test.ts`，退出0，输出7个测试文件101项通过；后续发现路径匹配包含 `.runtime` 历史源码镜像，因此该计数不能作为当前源码的准确基线。后续正式命令统一增加 `--exclude '**/.runtime/**'`，这是既有功能检查，不是新需求验收。文档写入后 `git diff --check` 通过。

### 当前执行进度

- [x] 任务1：时间证据服务端契约（规范、质量独立审查通过）
- [x] 任务2：Android原始事件与持久批次（规范复审、质量审查通过）
- [x] 任务3：取点择优与短距移动（修复后规范、质量复审通过）
- [x] 任务4：高度原始证据及协议兼容（规范复审、质量审查通过）
- [x] 任务5：目录、估算速度与地图（可见高亮修复后规范复审、质量审查通过）
- [x] 任务6：跨端回归、审查与APK交付（基线失败单列；未安装/部署，真机待验收）

实施前基线 HEAD `dc5a8202247021d30e3df91df18a4226602c9e86`，上一轮差异及39个已有修改/新增文件副本归档 `.runtime/usage-location-quality/baseline/`。服务端任务契约采用可选 `usage_evidence`，类别 `app_activity/screen_off/lock`，原始毫秒与具体usage来源；精确版本ACK独立于records确认；现有config增加usage_evidence能力以支持旧服务端平滑升级。具体字段和验证以任务1测试冻结结果为准。

任务1实现者正式报告：排除历史镜像后8个文件109通过、3个无关个人词库PG用例跳过，新增证据PG11项实际运行；TypeScript检查通过。仅本地 localhost/personal_ime 应用050，11列及索引、config能力接口已核验；未部署。证据 `.runtime/usage-location-quality/task1/`。独立复审尚未完成。

任务1根独立复验：5文件93项通过，新增真实PostgreSQL11项通过；规范与质量审查均通过，无阻断。进入任务2，保持服务端契约冻结。

任务2进行中：已复现证据表缺失、临时能力查询失败被误当旧服、切换目标 A→B→A 漏新版本等失败，并按测试修复；尚有旧服/普通记录链路用例待查，不宣称完成。定向 Gradle 使用临时 class include +单worker，避免 forkEvery=8 对全测试目录的发现开销；临时配置不改项目构建。原全量隔离配置仍留给最终回归。新代码须保留API23兼容，能力查询临时失败不能冷却两小时；只有已确认旧服才采用能力负缓存。

任务2实现者报告：43项定向通过，14类152项隔离回归通过。原始事件/游标事务、目标切回、精确ACK与能力边界已覆盖。根开始独立复审。

**测试网络边界：** 任务2早期测试因单调/墙钟夹具不一致，生成合成 `clock_changed` gap，可能尝试默认后台HTTP；是否抵达或写入未知。未部署、未生产登录/SQL/远端清理，但不能声称完全没有线上请求。详细时间、用例与影响范围在 `.runtime/usage-location-quality/task2/network-boundary-note.md`。已修正夹具，后续Android测试使用仅loopback的临时JVM guard，并检查启用与阻断日志；不修改系统网络或生产构建。

任务2审查发现并修复：可解析的错误配置对象不能缓存为旧服务器两小时。补真实旧config包络识别及错误schema用例，RED2项后14类156项回归通过；根独立修后47项通过，guard生效无阻断。两阶段审查已通过。进入任务3。

任务3冻结：11类171项通过；均衡服务5秒有界择优，policy与持久化同selected point，高质量GPS/fused双精度<=25m共享max(10m,精度和)。无可信速度、20m/分钟示例连续确认后0/120/240/360秒保存，不是每分钟必有点；机会式入口未新增选择窗。质量审查指出端到端延迟不能假定<=5秒，修正门禁为当前候选仍新鲜、实际入队间隔至少55秒、两采集至少60秒；不假设历史调度/IO延迟上限，回拨/密集/写盘失败保护保留。准确RED及冻结清单在task3证据目录。规范审查已通过，质量审查中。

任务3质量审查发现并正在复验修复两点：两两best选择会丢最新时间/未过期候补，现改有限32个时间点窗口集合，保留完整Location并按全窗最新15秒范围裁剪；另纠正5秒窗口不等于总交付/调度/IO延迟。新增准确失败场景后177项首轮通过，待最后冻结与复审。

任务3最终：两项质量阻断已修复，规范和质量复审均通过；根独立强制重跑177项通过，测试JVM网络隔离生效。进入任务4。

任务4最终：原始高度/垂直精度/类型与原因三端一致，旧接口按目标能力仅裁新字段；SQLite13→14逐目标wire pin避免ACK丢失重试hash碰撞，显式端点迁移保留pin。规范发现系统定位权限缺口已修复，GET后复查且无权位置不堵其他普通数据。45类469项Android全部通过，guard6active/0blocked；根独立Node63及真实PG11通过，两端build通过，双审通过。旧格式已ACK不补诊断metadata，新格式pin遇后台降级保留待传。进入task5前端，根并行执行最终Android全量验证。

最终回归阶段（进行中）：根运行 server build 成功。首次从项目根跑服务端全量出现两项依赖 server cwd 的既有路径失败（tsx 导入、字体路径）及一项并发渲染超时；未修改生产代码或放宽超时。改用 server 包原生工作目录、单 worker 运行完整 src，84文件919项通过、24文件331项跳过，日志 final/server-tests-correct-cwd.log；跳过项不能算通过，新契约真实PG11项另有 task4/root-pg.log 的独立证据。Android 全量仍在运行。

前端首次全量从项目根启动出现 login-proxy 的 Vite 工作目录启动超时及一项旧素材路由字符串断言失败；改到 client 工作目录且单 worker 重跑，480通过、1失败。剩余 sticker-materials.test.ts:496 期待静态组件，但 HEAD 的 main.ts 已是动态 import；两文件与 HEAD SHA256 完全一致，证据 final/client-baseline-evidence.json。不修改无关路由或断言掩盖基线。根已重建 client 并独立跑双宽度真实 Leaflet 浏览器通过，但规范审查另发现可见高亮遮挡（旧测试只看stroke属性），任务5正在按独立RED修复，以上不是最终批准。

Android 首次全量会话约20分钟以143终止，无完整结果，不能算通过；当时出现两项已知基线失败、guard41active/0blocked。根采用独立进程、文件退出标记重新执行同一完整命令，仍为单worker/1024m/forkEvery8/loopback guard，不增大测试超时或放宽断言；final/android-full-retry.log 与对应.exit记录最终实际状态。

任务5最终：9文件，62项定向通过；规范发现同坐标大圆遮挡选中小圆，以正式browser RED确认后对非交互视觉层bringToFront，初选及弹窗开/关刷新两轮都验证实际SVG顶层。规范与质量各自独立双宽度真实Leaflet通过，未知请求/页面错误0；根最终client全量480通过1上述HEAD既有失败，根browser-final双宽度通过。根目视390px地图及目录截图，布局无整页横溢且选择可见。当前跨端最终只读审查与Android全量重跑进行中，尚未构建新APK。

跨端最终只读审查批准，无Critical/Important/Minor。53文件中生产代码/正式测试与交审快照一致，实施计划由根继续更新；独立3文件40项跨契约通过，报告final/review/cross-end-review.md。原39文件基线逐一核对无缺失，24文件逐字保留，另15为本轮目标文件或计划的明确增量，详见final/prior-files-preservation.json。完成分支流程因既有失败不进入合并/PR，且本轮没有提交/推送授权，保持当前工作区。

最终Android全量：独立进程正常跑完24分49秒，Gradle退出1（测试失败而非中断）；403类3044项，3038通过、6失败、0error/skip。逐个class+test与上轮原始HEAD独立复现XML比对，六项集合完全相同，无新增失败（final/android-baseline-comparison.json）；失败分别为录音设置旧标题、GIF目录旧数、手动表情搜索旧接口、符号栏旧尺寸、微信列表旧协调器、候选栏旧drawable类型，未扩大任务修理。XML完整归档final/android-full-xml，guard51active/0blocked。新包构建开始，不把该全量表述为“全部通过”。


## 最终交付（2026-10-15）

- 推荐唯一新包：`E:\Projects\shurufa-android\apk\shurufa-2026-10-15-v20261015.17-2026101517-debug-bb8ab91e.apk`。
- 实际包名 `com.yuyan.pinyin.offline.debug`，versionName `20261015.17`、versionCode `2026101517`，minSDK23/target36，arm64-v8a。普通 assemble 成功，非 testOnly；API23/27/28/32/36均为固定原签名。
- SHA256 `bb8ab91e0a0e6f1f3c2ede3cf1500fdf1dfc8e14d24bc85830b45aa317cf0ad9`；WSL源与E盘一致后只删除本次WSL输出 `yuyanIme_2026101517_debug.apk`，E盘文件保留。证据 final/{apk-build.log,apk-verify.log,apk-badging.txt,apk-sha256.txt,delivery.json}。
- 最终验证：服务端919通过331跳过，客户端480通过1既有失败；Android3038通过6既有失败，无新增失败；前后端build成功（前端既有大chunk警告）。新功能62项前端定向/跨端40项及双宽度真实Leaflet通过。真实PG11项独立通过，不把跳过算通过。
- 未提交/推送、未连接或安装手机、未部署后台。后台新增时间列需先应用 `050_device_usage_evidence.sql` 后发布服务端/前端；本轮仅已核实的本地库应用。旧后台下客户端仍保持兼容，不应将仅安装APK称为后台新展示已生效。
- 后台升级前仅快照保持待传，已有旧格式位置确认后不补历史高度诊断；真实系统未提供的速度/高度、旧未采集事件不补造。短距离提高的是满足精度与连续证据条件时的保留能力，不保证每分钟都有记录，更不是导航精度保证。
- 真机步行/乘车/静置、移动网络与Wi-Fi熄屏恢复、到达延迟、宿主微信/抖音截图上传回归及实际耗电/流量仍待用户验收，本轮无真机证明。任务2早期合成请求边界仍见前文，不因交付改称从未访问线上。
- 本轮已确认要求写入项目 `AGENTS.md`“最近使用证据、定位质量与地图（2026-10-15 用户修订）”，范围限本项目；未改全局Vault规则。
