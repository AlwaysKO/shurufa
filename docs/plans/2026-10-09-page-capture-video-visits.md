# 微信/抖音页面采集与视频停留 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 已授权微信/抖音页面独立归档；视频省电首尾截图，缺图时仍展示可信前台停留时长。

**架构：** 保留聊天身份及统一持久化上传路径，增加与联系人身份隔离的页面记录。视频观看采用每次访问独立ID、单调时间计时、事件驱动首尾取帧，不用播放像素变化判断视频切换。结束记录可无图，不能被旧未确认聊天纯文字过滤器误删；权限撤回不补发。

**技术栈：** Android Kotlin/无障碍/SQLite、现有截图与网络队列、TypeScript/Vue、JUnit/Vitest。

## 已确认范围
微信/抖音列表、聊天、支付结果/账单、视频号/短视频、小程序等已授权页面；敏感输入页排除。视频进入稳定首图、划走开始尽力尾图，缺图标记；停留不等于精确播放时长。退出、下一条、切后台、锁屏结束；进程丢失标记不完整，不用下次启动时间推算昨夜结束。仅Wi-Fi上传，输入/游戏避让保留。

## 2026-10-09 线上诊断报告及范围补充
- 用户要求综合其提供的线上诊断截图继续实施，并明确：只扩展微信、抖音，不需要知乎、淘宝、拼多多；功能面向所有手机，不限两台荣耀。不得以当前调试设备序列号、机型白名单限制正式功能；各设备同意、权限和开关仍独立生效。
- 截图报告描述荣耀X80/荣耀200使用时长与入库图数量差异，仅作为用户提供的诊断线索；本轮未独立核验线上统计。使用时长不等于聊天操作，入库无图不能直接区分未采集、待传或上传失败。
- 合并实施顺序：先核实并修正滚动重复及可靠补传（含409资源恢复），再接低频浏览采集、缺图原因与独立“应用页面”展示；重复历史优先折叠，保留原图，不自动删除。
- 普通浏览拟用页面变化事件触发、稳定且输入空闲后采样；默认最短3分钟、每设备浏览总预算每小时20次/每天100次尝试（微信与抖音共享），重复图也计截图尝试。此参数来自报告建议，作为待验证的实现起点，不冒称最优或已启用。静态页面不定时重拍，同间隔只保留一次候选且执行时重验当前页，不事后补拍离开页。
- 视频不按播放像素变化触发，不轮询截图判重；原首尾图/可信前台时长需求保留。视频与普通浏览限频的协调须在接线前验证，不用3分钟浏览门槛阻塞聊天采集，也不能以视频路径绕过功耗保护。
- 区分未覆盖/证据不足、限频、重复跳过、待Wi-Fi、上传失败等原因；低电量/过热保护及预算状态须按现有系统能力核对后接入，不伪造已实现状态。真机比较耗电、发热及有效图片量，不承诺零额外耗电或所有短暂停留均有图。
- 荣耀200仅是当前真机验收样本；系统截图能力不足、权限缺失等兼容差异须可诊断，不宣称所有Android版本/机型均已通过。

## 当前事实及风险
- 当前工作区main HEAD=51dc2e5，保留此前标题计划的未提交验收记录。手机20261008.23，23:34:42安装；不得覆盖为旧版。
- 另一电脑已修改缺图恢复/去重和标题控件，需在此基础上最小接线，不恢复旧版本文件。
- 微信空页面树已现场出现，不能保证获得视频ID；必须核实页面类别、视频切换证据与敏感输入证据。无法确认切换不得把任意滚动或点赞计为新视频，也不得把动画每帧视为新页面。
- 无障碍事件到帧返回有延迟；末帧回调必须验证访问ID、窗口与代次，下一条帧拒绝。不通过取消守卫获取“更多截图”。
- 今日实际取证：23:00一家人聊天图、23:33列表图、09:09列表图均线上存在；不据此推断所有访问都采集成功。
- 完整采集/上报链路尚未完成，不能仅删除isChatPage检查就发布。

## 任务1：访问状态和计时（不依赖截图）
文件：新增 data/capture/page/VideoVisitTracker.kt 及同包单元测试，后续由服务集成。
1. 先写测试：正常43秒结束、无首尾图仍有时长、同视频事件/循环不重开、下一视频分段、切后台/锁屏、时钟倒退、进程恢复结束未知、旧访问帧不得串入新访问。
2. 跑定向单测观察失败；新增最小纯状态实现，时间通过显式输入传入，单调时间计时，墙钟只显示。
3. 上次未结束访问的持久快照恢复只提供不完整记录，不制造结束时间。单次活动访问和有界结束待办，不建无界内存队列。
4. 运行定向单测确认通过，记录与实际集成的区别。

## 任务2：页面证据与安全边界
文件：data/capture/ui/UiNodeSnapshot.kt；新增 data/capture/page/PageCapturePolicy.kt 与测试；media/WechatScreenshotIdentity.kt；对应抖音适配入口。
1. 检查真机微信视频号/抖音页及划动事件，不读取支付密码/验证码内容。
2. 先测试：聊天/list/payment/video/mini_app区分；密码/验证码/系统安全窗口拒绝；只有正文出现“支付/视频”不改变页面分类；空树未知页不无条件放行。
3. 将页面分类证据与联系人标题识别隔离，不把小程序标题或视频作者写成聊天联系人。
4. 识别不足明确降级为未知/证据不足，不伪造视频身份或停留起止。

## 任务3：事件接线、持久化与上传
文件：service/capture/PassiveChatAccessibilityService.kt；data/capture/media/WindowScreenshotter.kt；data/capture/CaptureCoordinator.kt；data/capture/UnconfirmedTextPolicy.kt；data/collect/LocalInputStore.kt（若既有事务交接足够，不改库结构）。
1. 先写服务/队列测试：进入、滑动开始、切页完成、后台、锁屏、断网/恢复、撤同意及重启。
2. 视频分类后暂停普通内容变化截图路径，仅事件驱动首尾取帧；非视频继续原去重/节流路径。
3. 首尾图采用同一visit ID但独立frame role；已切换视频后旧回调不能作为尾图。图未得到/未持久化分别标记，不把“请求成功”当“保存成功”。
4. 结束记录与已有图依赖持久交接；明确定义无图结束记录为page_visit，不套未确认聊天文字过滤。成功确认前保留待传；恢复逻辑沿用昨天修复。
5. 不改QQ，不绕过输入/游戏/有效Wi-Fi限制；不改自动发送/支付行为。

## 任务4：后台分类与展示
文件：server/src/domain/chatValidation.ts、server/src/api/chatDashboard.ts及相关scope；client/src/views/ChatCapture.vue；新增client/src/pageVisitSummary.ts和测试（实际存储复用方案须先审查）。
1. 先测试页面记录不参与联系人同名合并，设备/平台隔离；旧聊天查询不回归。
2. 非聊天记录独立分类；视频显示首图/尾图/缺失原因、前台停留秒数、完整性。不得将metadata写成无法查询的装饰而污染会话列表。
3. 最小化后端变更，若需迁移使用未占用新序号，不修改已部署042。元数据及纯结束记录均须实际验证服务端入库回执。
4. 验证分页、图片、删除范围与旧聊天相似折叠不会隐藏视频结束信息。

## 任务5：全链路验证与交付
1. Android串行：source /home/ko/android-tools/env.sh；cd android/YuyanIme；./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*capture*' --tests '*collect*' --offline。
2. 前后端相关Vitest和build；PostgreSQL用隔离实例，不写生产做测试。
3. 独立审查后构建，原签名/非testOnly/资源/版本/完整SHA核对；APK保存E盘约定目录。
4. 覆盖安装前明确新包范围，绝不卸载或清数据。真机由用户操作微信列表、聊天、支付结果（不要求真实新付款）、两平台视频连续划动/退出/锁屏；首尾和时长须对应同一次访问。
5. 手机代码完成、安装、服务器发布、迁移和真机验收分开报告。不自动提交推送混合工作区，不伪称上线。

## 执行状态（后续批次记录见文末）
- 计时/持久化/系统结束监听已实现；页面策略与候选帧组件已编写，当前以各批验证记录为准。
- 普通浏览窗口/滚动事件到持久预算、截图和本地队列已接线（第八批）；服务端独立页面接收及只读列表/原图API已实现、本机043迁移已执行（第九批）。手机页面上传/确认清理已代码接线并定向验证（第十批），未做手机到服务器实际联调；后台基础应用页面UI已实现并本地虚构数据浏览器验收（第十一批）；视频正式进入与首尾策略仍未贯通，缺图原因查询/历史折叠等尚未完成。未打包、安装或部署此功能。

## 现场证据（09:28–09:32）
- 微信视频号人工打开后，uiautomator成功返回只有com.tencent.mm空根、bounds=0的树。人工一次上划，对应09:29:26.491/.594/.727/.832四条滚动事件，from/to/count均为-1；09:29:27.135停止。前后诊断画面确实是不同内容。须合并滚动突发并核对页面证据，不能逐事件切访问。
- uiautomator获取树期间服务出现RESET/CONNECTED，后续取帧与事件验证不要将这次工具造成的重连当用户导航。
- 抖音截图有“图文”标志，不冒称已经取得普通视频样本。树dump因无法idle失败；同名输出仍为先前微信树，标为无效，不用于抖音推断。App自己的日志显示TREE=1随后PAGE_REJECTED，证明旧聊天筛选确实拒绝当前图文页。
- 诊断原图、日志仅在.runtime/page-capture-20261009，未放入正式素材或测试夹具；现场屏幕截图仅作为识别依据，不冒充App采集成功证据。

## 首批实现进度（尚未接入运行链路）
- 新增纯 Kotlin VideoVisitTracker 及定向测试，只有一个活动访问，不启动截图/定时器/网络；缺首尾图不影响可信前台停留时长。
- 首轮新API编译RED（类尚不存在），实现后9项GREEN；这不冒称首轮已观察到9个业务断言失败。
- 独立审查指出旧结束回调、乱序结束时刻、恢复覆盖已结束记录三项集成风险；追加3项测试均实际AssertionError RED，随后增加必需访问ID校验、最后观察时间守卫和已结束记录保留。最终12项定向回归通过，失败/错误/跳过均0，BUILD SUCCESSFUL（visit-final.log）；独立复审无本模块剩余阻断。
- 页面识别、事件接线、持久快照/结束记录可靠入队、后台独立分类及首尾展示仍未实施；当前模块不能独立宣称已支持实际视频计时或新增页面采集。
- 集成必须在进入前校验导航代次/页面证据，不能把旧异步观察重新enter；结束结果入队成功前可靠保留，不能让纯状态finish返回后即丢失。
- 用户需求已写入AGENTS.md及Vault跨项目入口对应索引，旧聊天计划的未提交验收记录保留，未提交推送、未覆盖安装或部署。

## 本批执行范围（结束边界与持久化）
- 用户补充：关闭应用、切换应用、熄屏都必须结束本次观看。事件时间在收到时冻结，不以后台执行延迟时间延长观看；强杀无法观测的终点保持未知。
- 增加独立 noBackup SQLite 访问日志，以事务绑定活动快照及结束待办；写入失败保留旧快照可重试，不依赖服务即将取消的内存队列。
- 结束边界必须按访问ID隔离；系统/输入法覆盖层不能仅凭事件包名被误判成切App。页面确认与系统结束事件分离，识别尚未接通前不宣称真机可用。

## 第二批验证结果：持久化与结束边界（尚未接系统监听）
- 新增 VideoVisitStore：noBackup SQLite，活动快照/完成记录事务提交；结束写入或切换后半段写入失败均回滚，旧记录可重试。重启恢复仅将旧活动访问记为不完整，不补造结束时间。完成记录有界读取、排序索引，尚未提供删除或远端确认入口。
- 新增 VideoVisitLifecycle：接收明确的应用窗口元信息及被冻结的访问ID/事件时钟；切其他App为BACKGROUND、回桌面为EXIT、熄屏为LOCKED，服务中断为INTERRUPTED。同宿主、未知窗口、输入法/系统覆盖层不会仅凭包名结束。它不是系统监听器；同宿主内离开视频由页面识别另行终结。
- 新API首轮RED均为类缺失的编译失败，不冒称业务断言失败。独立审查后追加索引红测（AssertionError）和拒绝旧回调不得重写快照红测（SQLiteConstraintException测试触发器），均实际见到失败后修正；事务后半段回滚和原熄屏参数重试也已覆盖。
- 最终串行执行 `:yuyansdk:testOfflineDebugUnitTest --tests '*VideoVisit*' --offline`：BUILD SUCCESSFUL，2分42秒；核对unix测试输出XML：Tracker 12、Store 9、Lifecycle 7，共28项，失败/错误/跳过均0。私有证据在.runtime/page-capture-20261009/visit-storage-final.log及对应XML。git diff --check通过；独立只读复审无本批模块级阻断。
- 尚未完成：页面分类、系统广播/无障碍监听接线、首尾实际取帧、可靠网络交接及后台独立展示。未跑完整capture/collect回归，未打包、安装、改手机开关、提交推送或部署。不能将本批单测当作手机观看时长已经生效。
- 后续集成：后台串行调用；初始化恢复先于进入；结束保存失败重试保留原ID/时间，不能被下一次进入覆盖；坏快照明确诊断不静默清库；持久完成记录必须在可靠交接后才清理，撤同意不补发。

## 第三批执行范围：系统事件监听
- 新增 VideoVisitMonitor，实际注册熄屏/亮屏广播、接收前台窗口变化；只冻结时间/代次，窗口核验和数据库操作交给共享串行后台 Handler。
- 单个失败结束边界保留原ID和事件时间，后续进入不得覆盖；30秒低频重试，无活动视频时不读窗口、不截图。
- 已确认的视频观察才允许启动计时，异步旧观察在窗口变化或熄屏后失效。窗口元信息无法对应原事件时标中断，不用后台执行时刻伪造精确结束。
- 服务重连复用监听实例；销毁排队收尾，不直接取消持久化任务。共享后台线程避免新旧服务实例的恢复/收尾交错。
- 本批仍不将包名当视频识别结果，页面识别与实际首尾取帧属于后续任务。

## 第三批验证结果：系统监听已接线，页面入口仍待实现
- VideoVisitServiceBridge 已接到 PassiveChatAccessibilityService 的窗口/滚动事件、onInterrupt、onServiceConnected和onDestroy。实际动态注册熄屏/亮屏广播；无活动访问不读窗口；不读取其他App内容，不新增截图或网络操作。
- 首轮监听器新API为编译RED，实现后定向通过。独立审查后追加“进入期间代次失效”和“熄屏已收到但close先执行”两项实际AssertionError红测；修正落盘后的状态复核、保留已排队结束边界、后台收尾取最新访问ID。另见到“覆盖层不能掩盖底层App切换”实际AssertionError红测后修正。
- 进入竞态测试通过交互状态检查回调注入边界，覆盖入口检查后失效的确定性重现；不冒称已做真机磁盘阻塞压力测试。
- 最终 `:yuyansdk:testOfflineDebugUnitTest --tests '*VideoVisit*' --offline` BUILD SUCCESSFUL（2分40秒）；核unix XML：Tracker12、Store9、Lifecycle7、Monitor11，共39项，失败/错误/跳过均0。包含真实Android广播派发、窗口事件时刻冻结、迟到观察拒绝、原时间失败重试与关闭收尾。编译仍有既有Android API弃用警告，不宣称零警告。
- 独立只读复审未发现本批接线阻断；git diff --check通过。测试与日志私有保存在.runtime/page-capture-20261009/monitor-*，未入Git。
- **当前仍没有正式页面分类调用 confirmVideo**：监听已接线不等于已产生真实视频访问。页面识别、首尾图片、可靠上报、后台独立展示及真机验收仍未完成；未打包安装、提交推送或发布。
- 后续接入后需覆盖高频窗口事件积压、失败边界期间新访问、同宿主内离开视频和撤同意禁传；保守中断结果不能展示为精确观看时长。

## 第四批执行范围与审查修正：页面识别及本地候选帧
- PageCapturePolicy 将页面类别与联系人身份隔离：列表使用上下导航多项证据，支付结果使用结果/金额/凭证字段，信息流与明确图文区分。MEDIA_FEED 只代表信息流，contentKey仍为空，不捏造视频ID。空树/缺证据/系统安全窗口/不支持App不默认放行。
- PageFrameProbe 在宿主窗口裁剪后仅做本地候选识别，安全分类后才编码返回；不写磁盘、不联网。已知密码或非聊天编辑输入会在物理请求前拒绝；空树候选只允许经已授权宿主和事件限频门禁在内存中识别，未知/敏感候选不输出。
- 首轮20项通过后，独立审查发现并通过5个实际失败测试修正：敏感提示标点/位数/繁体及相邻OCR拆分、密码标签加数字键盘、旧树不得拼出当前帧不存在的正向导航、OCR取消不能提前回收Bitmap。
- LocalPageFrameReader 已提供本地ML Kit/WindowScreenshotter连接器，但还没有服务调用入口。经复核，beginPreparation不是原聊天物理截图锁，因此新增 WindowMediaCapturer.tryWithPageCaptureSlot（复用原captureMutex，忙时页面探测直接放弃），reader必须传原服务capturer实例；不修改原聊天capture路径。
- 物理截图取消的额外AssertionError红测后，Probe改为等待系统回调结束、再检查取消和回收；current/同意epoch/输入游戏守卫也进入底层许可，包名严格限本次宿主。互斥槽覆盖系统回调及本次识别收尾。
- 尚未覆盖：实际小程序胶囊识别（目前仅策略接口接受明确证据）、账单列表全布局、稳定视频身份、实际事件调度/持久化/上传/后台展示。不能将返回候选图片能力称为扩展采集已启用；仍未打包安装或发布。
- 集成前须验证OCR占槽时长、输入/快速发送优先级、回调线程、事件限频及取消；低优先级不排队不代表已占槽后可抢占。新reader不得继承聊天SEND特殊许可。

### 组合回归测试夹具问题
- 首次组合回归停在新PageCaptureSlotTest等待截图源进入；保存jstack后终止该测试worker（退出143），不冒称通过。
- 添加前置断言后稳定得到 inputIdle=false、gameAllowed=true、elapsed=100；Robolectric跨类回拨虚拟时钟，而输入活动时间戳仍保留。最初在新测试@Before显式模拟一次输入后等待3001毫秒建立空闲前置状态，并保留门禁断言、等待超时；组合顺序反转后原MediaCropperTest同样受时间戳污染，8项表现为截图请求数为0，因此该类也增加相同空闲前置条件。生产输入/游戏保护未放宽，原输入避让用例仍在用例内重新触发输入并验证零请求。

- 独立只读复核确认截图锁/取消收尾修正无本批阻断；同时复核两处测试前置设置，未弱化原截图次数、互斥和输入避让断言。最终组合结果待下述新鲜输出，不沿用之前64项通过或被终止的运行。

### 第四批最终验证结果
- 串行执行 `:yuyansdk:testOfflineDebugUnitTest --tests '*capture.page*' --tests '*PageCaptureSlotTest' --tests '*MediaCropperTest' --offline`，BUILD SUCCESSFUL（2分5秒）。核对本次 unix 测试输出8个XML：页面策略17、候选帧9、共享槽2、原裁剪11、视频Tracker12/Store9/Lifecycle7/Monitor11，共78项，失败/错误/跳过均0。
- 日志及XML私有归档到 `.runtime/page-capture-20261009/page-regression-final*`，不入Git。这是定向单元/模拟Android回归，不是完整采集回归或真机验收。
- 当前批次提供页面分类、本地候选帧与共享截图槽，尚无服务调用 LocalPageFrameReader 或正式页面确认进入视频计时。视频分段/首尾调度、可靠入队上报、后台独立显示仍待接通；未为本功能打包、安装、提交推送或部署。

## 第五批实施步骤：浏览事件调度前置与既有补传回归
1. 核对 EventDelivery 的409处理及 LocalInputStore.requeueMissingChatAssets：只恢复当前目标和消息声明的依赖，消息不伪造成功；运行既有 EventDeliveryMissingAssetsTest，避免重写昨天已修路径。
2. 新增 `data/capture/page/BrowsingCaptureSchedule.kt` 和同包测试：仅微信/抖音明确的页面变化候选；单槽合并、稳定800毫秒、输入/游戏等门禁、旧代次失效；普通浏览尝试之间至少3分钟，聊天/视频不进入此调度器。
3. 先见RED再实现。尝试许可须由外部持久预算的原子预留回调提供；失败/重复也占尝试，预算拒绝不得取图。此批不以进程内计数冒充每日持久预算，未接通持久预算前不启用生产调度。
4. 追加测试：无变化不重拍、离开清候选、迟到事件不覆盖新页、稳定后延迟只采当前候选、预算拒绝不泄漏许可、图片失败不回滚间隔。串行回归页面模块及409恢复测试。
5. 后续接入持久预算、采集结果原因、事件/队列/后台；本批调度单测不冒充服务已启用。构建/真机/发布继续分开验收。

### 第五批中间证据与审查修正
- 现有 EventDeliveryMissingAssetsTest 覆盖409同目标图片重入队后重试消息、不重复注册、陌生/超限资源拒绝、全部引用确认前原图保留、原子交接复用/回滚及迁移恢复。代码核对和回归不等于已证实线上这次无图的根因；未修改这条既有补传路径。
- 新调度API首轮RED为缺类编译失败；实现后页面模块+409回归共82项通过（unix XML核对，失败/错误/跳过0，browse-schedule-green.log及browse-initial-XML）。
- 独立审查发现候选消费后旧事件可以复活，以及预算回调重入可越过正在建立的间隔。新增5项回归，15项中4个实际AssertionError（browse-review-red.log）；修正事件高水位与候选分离、拒绝消费时点之前积压事件、同代次窗口绑定和预留重入保护，异常finally释放。最终组合回归以随后结果为准。
- BrowsingCaptureSchedule 只接收明确页面变化事件，未提供定时器；只有候选且当前页匹配、稳定800ms、守卫允许、间隔满足且持久预算预留成功才返回一次尝试许可。预算拒绝/异常清候选须等新事件，不截图轮询；图片失败/重复不退还尝试间隔。
- 尚未接入正式服务；持久小时/每日预算、诊断原因持久化、视频分段与首尾调度、非聊天上报及后台独立展示仍未完成。进程内三分钟间隔不能替代跨进程持久预算；不得传恒true预留回调启用生产。真实截图前后仍须复核当前窗口代次、同意及守卫。

### 第五批最终验证结果
- 串行执行 `:yuyansdk:testOfflineDebugUnitTest --tests '*capture.page*' --tests '*EventDeliveryMissingAssetsTest' --tests '*CaptureCoordinatorTest' --tests '*ScreenshotContentCoordinatorTest' --tests '*PageCaptureSlotTest' --tests '*MediaCropperTest' --offline`，BUILD SUCCESSFUL（2分45秒）。本次unix XML共12个测试类、156项，失败/错误/跳过均0；其中新浏览调度15项，409资源恢复7项，另覆盖既有聊天/列表去重、发送上下文、页面候选帧与共享截图锁。
- 独立只读复核未发现本批剩余阻断；证据私有归档 `.runtime/page-capture-20261009/browse-final*`。定向回归不等于线上诊断已闭环或所有机型已通过。
- 未接通生产浏览调度、持久预算、非聊天上报与后台展示；未打包安装、提交推送或发布。后续必须接持久预留和真实事件代次，不得以恒true预算回调启用。

## 第六批：普通浏览持久预算
1. 新建 `BrowsingCaptureBudgetStore.kt`、同包测试；SQLite noBackup 独立预算库，事务预留，微信/抖音共用，无图片/文本/联系人内容。
2. 固定最短3分钟、滚动1小时20次/24小时100次，失败或重复不退还。读取设备boot count与elapsedRealtime；同一次启动只用单调时钟，不因修改日期刷新额度。
3. 重启保留旧额度：新boot只累加已知的新启动运行时间，不推测关机期间时长，因此关机长时间后的额度恢复可能偏保守；boot不可用、倒退或同boot elapsed倒退拒绝，不清库绕过。
4. 先写拒绝边界、重新打开数据库、跨boot、小时/日额度、写入失败回滚、多实例预留测试，RED后实现；串行回归页面模块。生产连接仍需真实事件与守卫，持久预留成功不等于实际取图。

### 第六批中间证据
- 新API首轮编译RED：BrowsingCaptureBudgetStore/BrowseBudgetResult不存在，budget-red.log；不冒称业务断言失败。
- 初轮实现后测试XML核对：持久预算11项、调度15项，合计26项通过，失败/错误/跳过0（budget-green.log，3分3秒）。包含真实双实例并发、系统BOOT_COUNT/elapsed入口和时钟元数据缺失不得清零。
- 补充跨boot保留全天额度、逻辑时间溢出拒绝及重建调度器+重开预算库组合测试后，正在串行组合回归；最终结果单独记录。
- 独立只读审查未发现模块阻断；同事务提交时钟、清理与预留，数据库错误上抛，异常不得视为许可。服务尚未调用持久预算；不能将模块存在当作已接通正式采集。

### 第六批最终验证结果
- 串行运行 `:yuyansdk:testOfflineDebugUnitTest --tests '*capture.page*' --tests '*EventDeliveryMissingAssetsTest' --tests '*CaptureCoordinatorTest' --tests '*ScreenshotContentCoordinatorTest' --tests '*PageCaptureSlotTest' --tests '*MediaCropperTest' --offline`，BUILD SUCCESSFUL（2分5秒）。核对本次unix XML，13类共170项，失败/错误/跳过均0，其中持久预算14项。
- 日志及XML私有归档 `.runtime/page-capture-20261009/budget-final*`，不入Git。git diff --check通过。未做完整capture/collect回归或本批真机验收。
- 已提供持久预算模块及调度器组合测试，尚未接正式截图入口；真实事件/视频首尾、持久页面上报与后台展示未完成。没有为本功能打包、安装、提交推送或上线。

## 第七批：非聊天截图可靠本地交接
- 核对后发现现有 `chat_messages` 会执行未确认文字过滤、聊天身份归组及图片依赖恢复；普通 `reports` 与输入事件也不是现成的独立页面协议。因此不能把页面记录伪装为聊天/输入事件后宣称上传已接通。
- 新增 `PageCaptureOutbox.kt` 及测试：noBackup SQLite 同事务保存页面元信息和图片，按平台包名/页面类型/原图hash去重；最多100条/32MiB待办，满时保留旧记录并明确拒绝，不静默淘汰未确认图片。只接微信/抖音、非聊天、已安全分类帧。
- 新增 `LocalPageFrameReader.readAndPersist` 连接实际读帧与此队列；保存前校验同意代次/当前页面，后台非取消事务完成才回报已保存，保存失败不冒充上报成功。该方法只在事件/预算门禁已许可时调用，不能成为无条件采集入口。
- 测试先RED：重开可读原图、同图不同包/类型隔离、重复不增量、容量保护、写入失败原子回滚、未知/聊天拒绝；随后测试读帧→持久化的成功/取消/失效路径。
- 正式服务启用仍须接到当前页面事件及持久预算，并实现非聊天服务端协议/上传回执；此批仅本地可靠交接，不新增网络或后台假记录。

### 第七批审查与真实失败回归
- 新Outbox API首先缺类编译RED，实现后18项定向回归通过（page-outbox-green.log）；初轮成功不能证明dispatcher交接边界安全。
- 独立审查指出：最终校验后返回原dispatcher时仍可因取消丢帧；整块读取3MiB BLOB可能超过CursorWindow。追加测试并实际观察21项中3个失败（page-handoff-red.log）：取消交接AssertionError、SQLiteBlobTooBigException、浏览继承聊天SEND特殊许可AssertionError。
- 修正为Probe最终校验后同步交接已接受帧引用，captureAndPersistAcceptedPage在capture退出的finally内做NonCancellable落盘；LocalReader.readAndPersist复用该路径。回调不做磁盘I/O，原物理截图槽已释放才写库，不把整段取帧改成不可取消。撤同意仍在事务前/提交前复核，不以普通导航取消冒充撤同意。
- image读取改64KiB分块、单图累计最多3MiB，检查长度及hash，拒绝损坏内容；Probe拒绝ChatCaptureAttempt上下文，不继承聊天发送特别许可。
- 32MiB限制为待办图片载荷，不是SQLite文件/WAL/临时副本的总磁盘上限。队列暂无上传确认删除入口；满时保留旧图、拒绝新增，不声称无限离线缓存。
- 当前只连接了LocalPageFrameReader到本地Outbox，仍未接服务触发/预算/非聊天网络协议/后台展示。启用前还须截图前容量检查、服务销毁等待已接受帧交接完成、上传前同意/Wi-Fi复核及远端确认后清理。未安装新包或部署。

### 第七批最终验证结果
- 串行执行 `:yuyansdk:testOfflineDebugUnitTest --tests '*capture.page*' --tests '*EventDeliveryMissingAssetsTest' --tests '*CaptureCoordinatorTest' --tests '*ScreenshotContentCoordinatorTest' --tests '*PageCaptureSlotTest' --tests '*MediaCropperTest' --offline`：BUILD SUCCESSFUL（2分52秒）。核对本次unix XML：14类共184项，失败/错误/跳过均0。Outbox及交接13项、Probe10项，含真实3MiB读回与取消边界。
- 独立只读复核未发现本模块剩余阻断；git diff --check通过。日志及XML私有归档 `.runtime/page-capture-20261009/page-handoff-final*`。
- 仍未接正式服务触发及非聊天上传/后台展示；本地保存结果不代表线上入库。未打包安装、提交推送或部署，不把本批定向回归当作荣耀200或其他机型真机验收。

## 第八批：普通浏览事件入口接线
1. 新增可注入依赖的 BrowsingPageDriver 及测试，窗口/滚动事件单槽合并；内容变化事件不触发截图，避免视频播放逐帧触发。待间隔/输入空闲结束后重新读取当前窗口，不使用旧树补拍。
2. 将 BrowsingCaptureSchedule 的剩余稳定/间隔等待暴露给driver；只保留一次事件触发的延后机会，没有变化不定时重拍。容量、已知敏感/编辑页、守卫先检查，再持久预留和真实读帧落盘。
3. 新增 BrowsingPageServiceBridge：仅核实微信/抖音活动应用窗口，后台有界读树，复用服务原WindowMediaCapturer；屏幕关闭/窗口切换使旧候选失效。使用同意epoch、输入/游戏、低电量/省电/过热守卫。
4. 接 PassiveChatAccessibilityService 的事件、连接/中断/销毁；退出等待已接受帧交接完成再关SQLite，重连先等前次收尾。保留原聊天路径，不使用浏览三分钟门槛阻塞聊天。
5. 先RED后实现，串行回归，独立审查后记录证据。普通浏览入口不等于视频首尾/精确分段已接通；本批无上传/后台展示，不打包安装或启用手机测试前谎称线上可用。

### 第八批中间验证及接线边界
- Driver API缺失先编译RED；初次组合37项中1项真实失败：上次消费与下一明确导航落同一毫秒，新候选被consumedThrough误拒。已按新导航代次放行同毫秒事件，旧代次重放仍拒绝。
- Window读取API缺失单独编译RED；补齐真实活动窗口包名/ID/类型核验后，页面/截图锁组合回归构建成功（page-driver-integrated.log），但审查后仍需补以下边界，不能沿用此结果称最终通过。
- 审查要求接可信聊天适配结果，防止语音模式聊天无editable字段时耗费浏览预算；空树仍无法在取帧前判定聊天，只允许预算内保守探测，不声称所有聊天已事先排除。
- 无包名TYPE_WINDOWS_CHANGED要后台单槽核验活动窗口后重触发，避免直接invalidate把刚进入页面的候选取消后不再调度。事件本身不提供包名时只读元信息，禁止猜App。
- MEDIA_FEED本批只是信息流浏览截图，不是视频首尾/视频分段；旧微信固定页/列表采集路径尚需与独立页面归档协调，发布前处理重复来源，不能将两条路径各自存图当成完整修复。
- 数据库及截图在进程级低优先级串行scope处理，主线程仅事件代次/取消/注册；关闭等待已接受图片交接完成后才关闭SQLite。当前仍无页面上传或后台显示，未安装新包。

### 第八批最终验证与剩余发布门禁
- 补充可信聊天与无包名窗口核验后，审查回归16项中3项实际断言失败，已修正；旧窗口元信息回调增加同一短锁下的导航代次/原同意epoch检查，防止迟到回调覆盖新导航或重新绑定新授权。
- 对上述最后两项测试做失效对照：仅临时移除 changedIfCurrent 门禁，13项中2项实际失败（lateWindowHintCannotReplaceNewerNavigation、windowHintCannotBeReboundToNewConsentEpoch），随后恢复并逐字核对源文件。证据为 page-driver-guard-negative.log/xml；失效实现未保留在源码。
- 最终串行组合覆盖页面、409恢复、聊天/列表去重、截图槽、发送时机、窗口后台读取及对方输入：BUILD SUCCESSFUL（3分6秒），unix XML共20类227项，失败/错误/跳过均0，其中Driver13项、活动窗口5项。外层shell此次回报exit1与构建日志不一致，未忽略：另以Python子进程明确记录相同完整命令退出0（增量UP-TO-DATE），不把增量核对说成重新执行227项。证据 page-driver-verified.log、page-driver-verified-xml/、page-driver-exit-check.log。
- 已接通服务窗口/滚动事件→后台稳定/空闲等待→持久预算→复用原截图槽→安全分类→本地Outbox；已识别聊天不消耗浏览预算，关闭等待已接受帧交接。CONTENT事件不采，尚不能宣称任意页面变化均被覆盖；空树仍需预算内保守识别。
- 只读审查未报告本批剩余阻断；git diff --check通过，原标题计划9行修改保留。仅自动化定向回归，不代表荣耀200或其他机型真机验收。
- 发布前仍必须完成：页面上传及远端确认清理/失败补传、后台独立分类与缺图原因、视频正式入口和首尾调度，以及旧微信固定页/列表路径协调。当前不打包安装、不提交推送、不上线，不将本地存图说成后台已收到。

## 第九批：独立页面服务端接收协议
1. 新增043页面表与移动端`/page-captures`接收接口：图片（最多3MiB）和元信息同一事务入库，避免先传图后消息引用丢失；按既有设备身份隔离，不借用聊天会话。相同设备/记录ID/完整载荷幂等，不同载荷返回409且不覆盖原图。
2. 仅允许微信/抖音与已支持非聊天页面类别；核验请求结构、时间、尺寸、hash、实际图片解码。不能只凭文件头接收坏图；不接联系人正文或视频播放时长字段冒充完整视频协议。
3. 后台增加受现有登录/设备作用域保护的只读列表和原图接口（每页20条，无图片Base64列表），本批不冒称UI完成。保存关闭遵守既有明确discarded回执，数据库异常不能返回成功；删除设备级联删除其页面图，不串其他手机。
4. 先写真实隔离PostgreSQL接口测试观察RED，再实现、迁移和回归/build；迁移先在隔离实例验证，再核对本机开发库身份后仅应用新增迁移。禁止把测试写入线上或自动部署。
5. 仍需后续接Android上传/确认清理及后台UI、视频访问正式入口/首尾和旧列表协调，当前不打包安装。

### 第九批验证结果与协议交接
- 新真实PostgreSQL集成测试先RED：6项失败，主要因新接口404，第6项因目标表尚不存在。初步实现6项通过，随后扩展到12项；所有测试均在新建Unix socket独立实例内执行，未使用业务连接。
- 最终 `bash server/scripts/test-page-captures.sh`：12项通过（page-api-final.log），包含并发相同ID同/不同载荷、单图3MiB上限拒绝、真实截断/双帧动画拒绝、PNG/WebP解码、事务及COMMIT阶段故障回滚、关闭保存绑定回执、设备/登录隔离、分页和设备删除级联，以及完整正式迁移链执行后聊天表零新增。新迁移在各最小schema重复执行以核实可重放。
- 动画夹具初版两帧相同被编码器折叠，真实失败发生在fixture的pages=2前置断言（11项中1失败），不是生产拒绝逻辑失败。改为两帧不同像素并保留前置断言后通过，未放宽生产代码。
- 独立审查发现测试硬编码ko角色，与initdb默认系统用户名不一致；已改为initdb/createdb/连接全部使用专用page_capture_test角色，复核无剩余阻断。实例停止后保留/tmp私有测试目录，不清理业务库。
- 原有5类组合回归初次66项中1失败：deviceIsolation.test.ts夹具只加载007，缺022的merged_into_id。临时移除本批app.ts接线后同样失败（page-api-isolation-baseline.log），确认既有夹具问题；恢复并核对接线，仅为夹具补一行022迁移，没有改聊天生产查询。最终66项全通过，覆盖聊天接收/后台、设备隔离、最近活跃、普通报告（page-api-regression-final.log）。
- 合计本批最终定向78项通过，server npm run build退出0；bash -n与git diff --check通过。未跑全仓库测试，本批没有重新跑Android；此前Android227项结果不冒充本批新证据。
- 根据项目本地迁移要求，先核对PG为localhost:5432、数据库personal_ime、实际地址::1、data_directory=/var/lib/postgresql/14/main，与本机pg_lsclusters一致；仅执行新增043（事务+3秒锁超时），字段和只读查询核验通过。没有改线上库或重新执行业务导入，证据page-api-local-migration.log。
- 协议交接：POST /api/v1/mobile/page-captures，沿用X-Device-Id；JSON字段id/package_name/kind/captured_at/width/height/sha256/mime_type/file_base64。kind为小写五类，captured_at毫秒，尺寸须与实际解码一致、最大8192边/1600万像素；时间不得早于2000年或超过服务端5分钟。ACK为ok/id/sha256，仅COMMIT后返回；discarded=true明确表示关闭保存，不当成已保存。不同完整载荷同ID返回409，不能因此删除本地图片。拒绝额外身份/正文/伪视频字段。
- GET /api/v1/dashboard/page-captures 和 /:id/image 已接现有登录与设备作用域；列表可platform/kind筛选、每页20项、无原图载荷，图片禁止缓存。本批是API，不是后台UI；尚无单张删除/历史折叠/缺图原因查询，不伪称展示工作已完成。
- 本轮未安装、未提交推送或上线。下一批需Android页面上传到此独立协议、目标/ID/hash严格回执确认后清理、有效Wi-Fi及共享限速守卫、失败保留，再接UI/视频/旧列表协调。

## 第十批：手机页面上传与严格回执交接
1. 增加可注入PageCaptureDelivery：每轮最多两图，准备阶段领取共享许可，释放后才申请上传许可；请求使用第九批独立协议，HTTP成功之外还须严格匹配布尔ok、ID、hash与可选布尔discarded。异常/超限/冲突/撤权/目标变化不能删图。
2. Outbox升级v2：保留原图，增加持久30秒失败退避；确认后同事务写有界内容去重回执再删除对应原图，避免上传清队列后重复截图再次入库。回执最多1000条，不存原图/正文；准备失败不破坏老图。
3. PageCaptureSync接既有DataCollector待办/唤醒/flush/cancel，仅当前线上目标、有效Wi-Fi、有效同意及App开关；共享图片单并发、限速、输入/游戏守卫，不开独立定时轮询。入队成功只唤醒既有同步。
4. 先RED再实现，串行Android定向/回归、只读审查。服务端协议虽已本地验证，仍不代表真机或线上已交付；UI/视频入口/旧列表协调继续待完成。

### 第十批最终验证与接线边界
- 新Delivery API缺失先编译RED（page-upload-red.log）；初步交接/Outbox组合通过。审查补测后14项中5项失败（page-upload-review-red.log）：墙钟回拨积压、回执尾随垃圾未拒绝，以及三处生产接线缺失（含Sync文件不存在）。随后实现并验证，不将API编译失败冒称业务断言失败。
- 修正due为单条有界元信息查询，无逐行N+1读取；retry_after距离当前墙钟超过30秒时允许重新尝试，避免时钟回拨长期搁置。新v2迁移仅加列/回执表，保留v1原图；成功确认的去重回执按最新1000条有界保存，超出范围不承诺无限历史去重。
- 回执只接受2xx、严格布尔ok、完全匹配ID/hash及可选布尔discarded，拒绝陌生字段、尾随垃圾、错误UTF-8及超过4KiB响应（未知Content-Length同样有界）。disabled的discarded单独计数，不当成已存储；故障/409/失效回执保留原图并30秒持久退避。
- ACK事务写小回执再删对应原图，提交前再次验证作用域，撤权回滚；测试直接查询receipt表确保无残留。修正原坏响应测试共用退避导致后两例未真正发送的问题，现每例独立建库并断言发送次数。
- PageCaptureSync复用DataCollector待办检测/恢复唤醒/flush/cancel，只发当前线上目标、不增加定时器；persistAcceptedPage确认事务完成且SAVED后requestSync。冻结同意epoch、服务器目标epoch、取消代次、配置revision，当前开关与有效Wi-Fi逐块检查；ServerConfig切走再切回会推进epoch，旧轮不能重新确认。
- 新路径归入共享截图image分支：准备/读图/JSON与Base64及UTF-8编码避让输入，完成后释放准备许可再领取上传许可；上传直接使用准备好的ByteArray，不重新解析/编码大JSON。已开始的图片上传不因输入取消，继续共享单并发、亮/熄屏限速、Wi-Fi绑定socket与游戏保护；禁用HTTP重定向，防止携图跳转其他目标。
- 初轮集成模块及共享守卫组合18类164项通过（page-upload-integrated.log/XML）；最后加入响应边界、目标epoch测试并完成字节准备后重新运行，不沿用旧结果。最终串行命令包含 *capture.page*、*ImageUpload*Test、CollectorFlushSignalTest、ServerConfigTest、ScreenshotUploadDeviceIdleTest、EventDeliveryMissingAssetsTest、CaptureCoordinatorTest、ScreenshotContentCoordinatorTest、PageCaptureSlotTest、MediaCropperTest：BUILD SUCCESSFUL（2分59秒），退出0。unix XML核实25类248项，失败/错误/跳过均0；Delivery11、Sync3、源码接线3。源码接线测试不冒充真机网络行为。
- 独立只读审查无本批剩余阻断；审查期间“入队未唤醒”误报经核对persistAcceptedPage现有调用后已撤回。git diff --check通过；日志及XML私有归档.runtime/page-capture-20261009/page-upload-*，不入Git。
- 尚未做Android真实请求→独立服务端→手机队列清理的联调，没有安装或上线。仍须后台应用页面UI、视频正式进入/分段与首尾调度、缺图原因持久查询、旧微信固定页/列表协调及整体验收。已完成的代码接线不等于荣耀200或所有机型实测通过；无提交推送/打包/部署。

## 第十一批：后台应用页面独立展示
1. 新增“应用页面”菜单/路由，与联系人聊天列表完全隔离；只读查询第九批接口，当前手机、微信/抖音、五种页面类型筛选，每页20条，查看原图及采集/入库时间。
2. 用小型可测试加载状态控制器阻止旧手机/筛选/页码响应覆盖新页面；切换手机/退出登录/卸载立即清空列表和原图。URL显式冻结手机作用域，图片失败显示失败而非空白冒充无记录。
3. 不显示未实现的视频时长或伪首尾；信息流明确不等于已确认视频。未接入的漏图原因/历史折叠/删除功能不放假按钮，本批不改变聊天查询。
4. 先测试状态/菜单路由接线RED，再实现、前后端构建与定向回归；不部署或宣称手机到线上验收完成。

### 第十一批最终验证与展示边界
- 新模块缺失先导入RED（page-ui-red.log），不称8项业务断言失败。实现独立PageCaptureBrowser状态、显式手机作用域URL、dashboardFetch列表请求、原图卡片/弹窗、筛选/分页和源码菜单路由；没有接到聊天会话或伪造视频时长。
- 初版测试放server/src并静态引用client源码，引起服务端TS6059 rootDir构建失败；按既有前端测试布局移到client/tests/page-captures.test.ts，清理仅本次TS误生成的pageCaptureBrowser.js/map，未扩大tsconfig编译范围。前后端最终构建退出0；前端保留既有大包体积warning，不据此称性能已验收。
- 独立审查发现原图error动态读取当前selected：本地Chromium真实DOM事件复现旧图错误误伤新图（page-ui-browser-red.log）。改为单项v-for冻结selectedRow及设备/记录key，旧DOM错误只作用于所属记录；浏览器及新增回归验证通过。仅加key不作为修复。
- Chromium脚本client/tests/page-captures.browser.cjs只启动临时127.0.0.1静态服务，所有API使用虚构设备/记录/图片与登录夹具；不读业务配置、不连业务数据库或线上。覆盖20条列表、鉴权包装、图片失败重试、弹窗/ESC、旧错误隔离、平台/类型/翻页、切手机清旧图、退出登录和桌面/390px窄屏。生产构建真实Vue渲染，不冒充真实服务器/手机上传验收。
- 窄屏截图发现原后台固定侧栏把本页挤到不足300px，新增浏览器宽度断言实际RED（page-ui-mobile-red.log）。仅对.layout:has(.page-captures)采用已有素材页的顶部导航思路，未改其他路由；补筛选aria-label。浏览器夹具曾因两个同名h1/h2选择不明确失败，改为指定level=2，不放宽生产断言。最终浏览器脚本退出0；桌面/窄屏截图已实际查看，均为虚构数据。
- 最终前端定向命令显式排除**/.runtime/**，避免误运行归档副本：4类133项全部通过（含新10项），失败/跳过0，page-ui-current-tests.json核对。此前未排除时157项含24项旧归档，不计为当前产品额外覆盖。另重跑独立PostgreSQL页面API12项全部通过（page-ui-api-regression.log）。本批合计145项定向回归，未重跑Android全套。
- 独立静态复核无剩余本批阻断；git diff --check通过。入口/路由随源码交付，无需手工配菜单。证据私有归档.runtime/page-capture-20261009/page-ui-*，浏览器不复用个人会话。
- 未部署/未安装/未提交推送。本页只展示服务器已保存截图，空列表不能直接诊断手机未拍或上传失败；不显示尚未实现的缺图原因、历史折叠/删除或视频时长。下一步仍须视频正式识别/访问首尾接线、旧微信页面路径协调，以及真机到线上联调验收。

### 插入修复：本机设备目录缺表（2026-10-09）
- 用户反馈 deviceDataReceivedAt 报 42P01/navigation_record。读取运行中 tsx 子进程的连接环境并按实际 dotenv 优先级核对：localhost:5432/personal_ime，PostgreSQL 14/main，回环地址。未输出凭据、未连接线上。
- 本机已存在043页面表，但旧038导航、039通话与041清理墓碑表缺失。上轮仅执行新043未检查旧查询依赖，不能将新表存在视为完整迁移成功。启动入口 start.sh 已包含 migrate，直接运行服务本身不自动迁移；本次未追溯证明历史缺迁移由哪次启动造成。
- 先用实际 deviceDataReceivedAt 对本地现有设备重现同一42P01；随后在单事务、5秒锁超时/60秒语句超时内执行既有038、039、041、042_device_data_received_indexes。仅新增缺失表/索引，未删历史、未修改查询吞错、未导入素材或执行其他无关迁移。
- 执行前地址守卫初次因inet的::1/128文本形式中止，未写库；改用host(inet_server_addr())归一后确认身份才执行。修复后同一函数对3台设备查询成功，四张缺表均存在；对原运行服务登录并请求 GET /api/v1/dashboard/users，实际HTTP200，随后退出诊断会话。
- 专用临时PostgreSQL回归：test-chat-retention.sh + deviceDataReceived.postgres.test.ts，2类29项全部通过、0跳过；git diff --check通过。证据为.runtime/page-capture-20261009/local-schema-*与local-directory-verification.log（私有归档）。无需重启服务。本次只修本机数据库，不代表线上已迁移或手机漏采已验收。

## 第十二批：微信列表退出旧聊天归档
- 先针对可读树固定列表、空树OCR主标题、旧像素身份缓存补红测，再使微信列表仅走独立浏览路径；真正有聊天控件的同名联系人保留。原历史记录/待传队列不删除。
- 本批不把朋友圈旧路径同时关闭：其独立分类尚未补齐，另行核对。列表使用既有浏览稳定/预算/授权守卫，不保证每次短暂停留均保存图片；不扩大普通浏览频率。
- 验证适配器、OCR标题、页面策略/窗口与截图回归。仍需真机端到端验收，不将去除旧归档误称为视频首尾已完成。

### 第十二批中间验证与边界
- 首轮3类54项运行中新增3项均实际AssertionError RED（列表适配器、OCR主标题、持久身份），最终任务退出1。随后停止一个误提前启动的组合任务（退出143，不计验证），后续各次等待前一任务结束再运行；私有日志保留。首轮线程快照显示测试框架仍在类资源查找，并非截图死锁，不据此改生产线程策略。
- 树适配固定列表拒绝、稳定身份列表标非聊天、OCR主标题列表拒绝；resolver使用identity.isChatPage与当前帧判定的逻辑与，不能把缓存中已否决的列表再次变成聊天。原历史与队列不清理，朋友圈旧路径暂保留。
- 下一轮发现同名“微信”真实聊天的新断言ClassCastException：原NON_TITLES黑名单仍含微信。独立审查先前关于“同名聊天已保留”的静态结论因此撤回；在已有明确聊天控件/同层输入/标题/截图区域守卫后移除该名称黑名单。没有放宽固定页面分支。另两条旧OCR测试期望列表允许，按本轮独立归档要求更新为拒绝，其余聊天/裁剪断言保留。
- 旧列表别名“微佳”若只退出聊天而新浏览不接收，会造成漏接。新增完整导航/缺一项导航/明确聊天优先测试，实际AssertionError RED后，PageCapturePolicy仅将顶部标题判断复用既有canonicalWechatPageTitle；底部四项导航及敏感输入/secure/chatVerified优先级不变。
- 本批防止确认列表新入聊天归档，不保证空树只产生一次物理探测（旧入口仍需识别后拒绝），也不保证仅凭无法辨认的标题就能确认列表。无可读聊天控件时，单独“微信/微佳”标题与同名联系人仍无法可靠区分；此时保守拒绝聊天，不能宣称该情况全覆盖。下一步需结合共享帧/可信页面证据协调取帧，而不是去掉安全或身份门禁。

### 第十二批最终验证
- 串行最终命令：`:yuyansdk:testOfflineDebugUnitTest --tests '*capture.page*' --tests '*WeChatChatAdapterTest' --tests '*WechatTitleStabilizerTest' --tests '*WechatScreenshotIdentityTest' --tests '*NotificationScreenshotFallbackTest' --tests '*EventDeliveryMissingAssetsTest' --tests '*CaptureCoordinatorTest' --tests '*ScreenshotContentCoordinatorTest' --offline`；BUILD SUCCESSFUL（3分32秒）。仅统计本次Unix XML：21类274项，失败/错误/跳过均0。证据list-routing-final.log与list-routing-final-xml。
- 独立最终只读复核无本批剩余阻断；git diff --check通过。未运行全量capture/collect、未打包安装/提交推送/部署，不声称视频首尾、旧朋友圈迁移或真机漏采闭环已完成。

## 第十三批：视频访问元数据纵向闭环（2026-10-09）
- 改为在固定协议下并行处理互不写同文件的手机可靠上传、服务端入库、后台展示；主线程核对真实识别入口与证据。Android构建仍由主线程串行执行，禁止并行Gradle。
- 新协议 POST /api/v1/mobile/video-visits（已有设备身份）：严格对象字段 id(UUID), platform(wechat/douyin), entered_at(毫秒), ended_at(毫秒或null), duration_ms(非负安全整数或null), exit_reason(switched/exit/background/locked/interrupted), complete(boolean), first_image_id(UUID或null), last_image_id(UUID或null)。只发送已结束记录，不上传本地videoKey/elapsed/正文。正常结束complete=true且时长已知；interrupted必须complete=false且ended_at/duration_ms均null；非法时钟非interrupted可complete=false且duration_ms=null。时间显示不与单调时长强行相减相等。
- ACK {ok:true,record:完整已验证请求对象,discarded?:true}，客户端严格核对record全部字段，不凭HTTP200或ID清队列。同设备/id相同载荷幂等、不同载荷409；提交后ACK。已有图片引用只允许同设备同平台已存在page_capture，缺依赖返回409明确原因，记录保留；当前未接首尾时均null，不制造图片。
- GET /api/v1/dashboard/video-visits：设备隔离，platform/page，固定20条，返回{total,page,page_size,records}，records为请求字段加received_at。独立后台视频访问页面，明确“前台停留，不是播放时长”；缺图及不完整如实显示。图引用走既有page-captures/:id/image带设备作用域。
- 本批不把MEDIA_FEED直接认作视频：没有视频/切换可靠证据，仍不调用confirmVideo，不生成假访问。元数据链路完成与真实视频采集贯通分别验收；不能因用户催进度而伪造能力。

### 第十三批并行实现及现场补充（进行中）
- 固定协议拆成不共享文件的Android投递、server入库、client展示，并行实施；Gradle由主线程串行。Android首轮因videoVisitPayload/VideoVisitDelivery缺实现产生编译RED，未冒称业务断言失败；实现后首轮20类215项通过（video-delivery-green.log）。
- 服务端新增044及POST/GET，独立PG视频9/页面12/接收时间+清理31项通过；新增平台数组非法值实际500 RED后改严格字符串400。UI新增独立视频访问菜单/路由，144项前端回归、build、Chromium虚构API验收完成；不代表真实手机上报。
- 主线程已核验运行中服务继承的本地连接（watch进程8130，localhost:5432/personal_ime，::1，PostgreSQL14/main），单事务、锁/语句超时执行044；video_visit/page_capture存在，设备接收时间查询对3台设备成功。原运行服务登录验证/users、page-captures、video-visits均HTTP200，未打印业务内容、未往业务库注入测试记录、未改线上。
- 实际手机须用Windows ADB，Linux ADB设备列表为空。首次用户反馈已打开时只取得荣耀AOD熄屏树，未把微信前台/红包活动当抖音视频。再次用户亮屏后确认为抖音SplashActivity，截图确为普通视频；uiautomator未生成目标树文件，明确标无效，不复用先前旧树。媒体会话为STOPPED且metadata=null，不拿此数据生成视频内容ID或准确分段。样本只在.runtime/page-capture-20261009/douyin-video-live.png，已查看，私有保存。
- 真视频截图顶部带“直播”导航，当前PageCapturePolicy把任何“直播”标签都拒绝，可能漏掉普通抖音信息流。本轮补顶部导航不拒绝/内容直播仍拒绝的RED；仅改善MEDIA_FEED分类，不把信息流冒称已确认单视频。
- 主线程审查另发现投递公平性风险：先LIMIT20再过滤关闭平台会挡住后续启用平台；固定前2条30秒退避在>=1分钟同步间隔下会饿死未尝试记录。新增两项实际场景RED后修SQL平台过滤与尝试优先次序，最终验证另记。

### 第十三批最终验证与未完成项
- 导航误判与两处队列饥饿的组合RED：3项实际AssertionError（video-fairness-navigation-red.log，最终退出1），不是编译错误。修正仅忽略顶部“直播”导航作为直播内容证据，内容区“直播”或“正在直播”仍拒绝单视频推断；仍只分类MEDIA_FEED，不生成视频ID。
- VideoVisitStore v2一次新增retry_after/platform，v1逐行回填平台而不改payload/active；SQL先选启用平台再按retry_after、entered_at、id排序。每轮2条、hasDue同筛选，避免关闭平台挡后续与失败旧条反复独占。v2仅本轮开发、未安装发布，未将中间schema当现行版本升v3。
- 最终串行Gradle：`:yuyansdk:testOfflineDebugUnitTest --tests '*capture.page*' --tests '*EventDeliveryMissingAssetsTest' --tests '*WeChatChatAdapterTest' --tests '*WechatTitleStabilizerTest' --tests '*WechatScreenshotIdentityTest' --tests '*ImageUpload*Test' --offline`，BUILD SUCCESSFUL（2分49秒），Unix XML23类231项、失败/错误/跳过0。证据video-delivery-final.log/同名XML目录。编译保留一处新Store.defer异类arrayOf类型推断warning及既有warning，不称零警告。
- 后端最终52项、前端144项、两端build通过；主线程复核日志及虚构数据手机宽度截图。视频UI/server协议、Android可靠ACK/平台公平队列/守卫经交叉只读审查无本批剩余阻断。git diff --check通过。
- 本批已接视频已结束元数据的手机持久化→可靠投递代码、服务端事务ACK→后台独立展示；本机044及运行服务GET实际验证完成。但没有真实confirmVideo识别调用，尚未产生或上传真实视频访问；首尾取帧/图片引用调度与可靠单视频切换仍未贯通。手机与真实服务器的实际HTTP闭环、全量回归、打包/安装/线上发布仍未做，不把合成/单元/隔离PG测试冒充真机通过。
- 已告知用户样本完成可正常使用手机，不继续未经约定采集其他页面。另一任务新增的redpacket-virtual-display计划及红包相关变化不是本批工作，不修改/提交它们。

## 第十四批：访问进入与图片归属（进行中）
- 先补持久化进入时首图原子绑定：已安全落盘的页面图片ID与访问在同一事务保存，连续同一访问不得替换首图，结束后不迟到改写已上传载荷；保留先前生命周期/回执守卫。
- 主线程已就空树无法确认单视频的情况向用户询问是否允许明确标注的“信息流页面停留”降级，尚未得到确认前不将MEDIA_FEED当video、不更改后台视频协议来冒充精确记录。

### 第十四批用户确认修订：信息流停留降级
- 2026-10-09用户明确允许“信息流页面停留时长（未确认单条视频）”，仍保存符合条件截图。不得混入单条视频统计；仅从已安全分类的MEDIA_FEED帧建立观察，不根据包名或任意滚动猜页面。
- VideoVisit/协议新增observation_kind = confirmed_video | unconfirmed_feed。新记录明确传值；后台标题改为“视频与信息流停留”，每条显示类别。新增exit_reason=page_changed表示本宿主滚动/窗口变化观察边界，不叫“切换视频”。墙钟/单调计时、有效同意和退出/锁屏规则继续保留。
- 现有普通浏览的已接受帧安全落盘后，图片ID与observed_elapsed/wall冻结值交给monitor，首图原子绑定。新入口只生成unconfirmed_feed，暂不生成confirmed_video。仅SAVED帧启动，DUPLICATE不借旧图建立新访问；仍受原3分钟/总预算，无法声称每条视频或每次短停留全覆盖。
- 同宿主滚动或应用窗口变化结束已观察段为page_changed；系统/输入法覆盖层不误结束，已知切App/熄屏保持原确切结束原因。迟到帧、撤权、旧代次不得启动。尾图暂无可验证来源则null，不借下一页或首图冒充尾图。
- 服务端新增045字段/约束迁移，固定API请求新增必需observation_kind；本功能此前从未安装发布，旧测试与前端协议同步升级。既有表历史列默认confirmed_video（原协议仅允许确认视频），不改原时长/原图。完成前自动执行本地迁移并核验实际接口，不修改线上。

### 第十四批协议与本地接口验证
- 新增045已在核实的本地PostgreSQL14/main回环连接执行，未触碰线上。2026-10-09主线程再请求原运行服务/users、page-captures、video-visits均HTTP200；未往业务库注入测试记录。
- 主线程复跑隔离PG视频接口11项、前端视频/页面22项通过。信息流与确认视频分别显示，退出文案改为中性“退出页面”；该文案先实际断言RED再GREEN。client生产build成功，既有大chunk警告仍在。
- 独立复审指出撤权再授权的排队旧帧与系统覆盖层问题；已新增冻结授权/策略epoch、真实覆盖层检测与同底层窗口拓扑事件处理。又发现新合法帧进入时旧epoch活动观察可能被正常结束，正在补专门回归与修复，不将中间编译/测试结果当最终完成。

### 第十四批最终代码回归
- 新帧替换旧epoch访问的回归实际AssertionError RED（feed-epoch-transition-red.log，退出1）；confirmVideo现在先将旧授权/策略观察以INTERRUPTED持久化，失败则保留待收尾且拒绝新进入，成功后才接受新帧。同一测试同时验证同意与配置epoch变化，不把撤权间隔算入时长。独立最终只读复审无本修复剩余阻断。
- 主线程串行最终Gradle：`:yuyansdk:testOfflineDebugUnitTest --tests '*capture.page*' --tests '*EventDeliveryMissingAssetsTest' --tests '*WeChatChatAdapterTest' --tests '*WechatTitleStabilizerTest' --tests '*WechatScreenshotIdentityTest' --tests '*ImageUpload*Test' --offline`，BUILD SUCCESSFUL（3分6秒）。本次Unix XML23类241项，失败/错误/跳过均0；已归档feed-entry-verified.log及feed-entry-verified-xml。前一次240项成功未含最后新增epoch切换测试，不混算。
- PageCaptureOutbox重试顺序由captured_at优先改为retry_after优先（普通pending浏览次序不变），连续一分钟多轮与重开SQLite测试先实际失败再通过；避免最早失败图片每轮独占上传机会，未删除图片或伪造成功。
- 手机生产路径已从合规MEDIA_FEED新图落盘接入unconfirmed_feed观察并原子关联首图，结束元数据沿可靠投递进入独立后台页面。不代表手机已运行新代码。确认单条视频识别及可靠尾帧来源仍未实现；尾图null、受3分钟与共享尝试预算约束的部分覆盖均明确保留，不声称逐条视频全覆盖或所有手机已验收。
- 正在按项目固定原签名构建可安装阶段测试包；尚未安装、提交/推送或线上部署，真正手机到线上HTTP闭环仍待验证。

### 第十四批阶段测试APK交付
- `:app:assembleOfflineDebug --offline`成功（1分17秒）。阶段包：`E:\Projects\shurufa-android\apk\shurufa-2026-10-09-v20261009.16-2026100916-debug-ccd29bd7.apk`。
- APK实际包名com.yuyan.pinyin.offline.debug，versionName20261009.16/versionCode2026100916，与output-metadata一致；非testOnly。独立脚本核验API23/27/28/32/36均为固定原证书。
- 源/交付SHA256完全一致：ccd29bd75da8142741ec1f29af8f20ddcbcf17bba8873f9cf8404c7b9e0a18b0。依项目规则只删除本次对应WSL构建APK，保留E盘交付文件；未删除其他历史APK。证据feed-apk-build.log、feed-apk-verification.json。
- 本轮交付的是已通过上述定向回归的阶段测试包，不是全量验收或手机已安装证明。线上新API/043–045迁移与后台菜单尚未部署；此包未安装，未提交/推送Git，不声称新页面截图或停留记录已在用户线上可见。

## 第十五批：并行收尾与授权发布（2026-10-09）
- 用户明确要求使用子代理加速，并授权“提交、推送、发布并覆盖安装”；不包括另一任务的红包变化、历史标题计划已有9行改动。采集入口、可靠投递/诊断、后台发布准备按不共享文件分工，主线程串行Gradle和真机联调。
- 现场样本暴露导航OCR可能把多个tab合成一行、微信已关注作者没有底部+关注；两项实际AssertionError RED后只按同帧同区域空白分隔导航处理，并增加完整四tab证据，未按正文关键词放行。
- 补齐账单（标题+年月+收支汇总+带日期正负金额）和标准深浅小程序胶囊（同帧圆环/中心点/三点）入口。账单实际RED；小程序旧实现返回null，原测试!!导致NPE，核对根因后改为明确非空结果断言，再补实现。OCR无结果、敏感输入、聊天页拒绝不变；彩色自定义导航仍不保证覆盖，不宣称所有版本/页面绝不漏采。
- 复用/chat/diagnostics新增browse_capture/browse_upload，无新表/正文/图片字段，2平台×6阶段=12槽，兼容旧8槽缓存；Reporter仍每轮最多8条/5秒，按本次token包名归属不串平台。展示间隔/预算/队列满/重复/未覆盖/等待Wi-Fi/失败/回执；最近结果不是全队列状态，离线诊断需联网后补传。队列满明确已有图片保留、本次未入队。
- 新增跨队列409→页面图ACK→DB重开→访问ACK回归；不将图片依赖暂缺当永久失败，不清除未确认记录。后端诊断32、隔离PG页面12/停留11通过，server/client build成功。
- 最终Android26类253项全部通过、失败/错误/跳过0（page-release-android-final.log/同名XML，3分26秒）。包含全部capture.page、ChatDiagnosticsBuffer/Reporter、旧聊天适配器/身份、缺资源/图片上传定向回归；非全量机型验收。
- 后台29个相关文件已提交e32e0c2并推送origin/main，等待生产timer实际发布；未用本地登录失败或匿名401冒充线上新API有效。线上公开资源与手机真实ACK将独立核验。手机当前已核实为旧20261008.23、原同意开启、原无障碍开启、上报仍指原生产域名，未改任何采集/上传设置。
