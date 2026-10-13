# 输入法静默红包代码交付

## 当前通知驱动重写（2026-10-10，手机已带走）

用户已要求继续离线完成，后续统一验收；不再发起手机操作或要求新测试红包。本次 AUTO/通知 PROBE 接线已改为：真实系统通知 → UID/PendingIntent 创建者认证 → 通知账号的 PUBLIC 隔离副屏 → 原生无障碍控件 → 双次核对 → 持久预留 → 卡片/拆开各一次 → 独立结果页复核。默认自动账号与所有通知群，不要求填写群名，不以群名 OCR 为领取前置。旧 PRIVATE/OCR 仅保留手动只读诊断。

当前实现与验证范围以[通知原生控件计划](../plans/2026-10-10-redpacket-notification-native.md)为准；下文 `.15/.16` 及更早版本均为历史证据，不能代表此版真机效果。PUBLIC 副屏是否可被当前微信、分身及各厂商无障碍读取，真实到账、游戏并行体验和功耗仍待后续实机验收。没有本版领取成功证据。

### 已定位问题与对应修正

- 本轮连接时手机安装 `.17`，设置实际为 OFF；真实新通知留下 `NOTICE_SEEN=1/MODE_OFF=1`，任务未开始。先前诊断临时关闭后的恢复不完整属于开发流程问题，不能把这种未运行误作识别失败。已补准确的持久模式/授权/运行状态及保存失败提示。手机带走前最后一次成功清理记录为 AUTO、USB保持亮屏恢复0、本次临时JAR删除；断开后无法再次核验，不声称新的清理或安装成功。
- 正确测试群的原生尺寸只读资料页中，ML Kit 将群名右侧箭头并入同一个 element：原名4码点，OCR行5码点、前缀完全一致、尾部1个箭头。因此旧群名 OCR 的拒绝并非用户填错群，也不采用剥尾或硬编码纠错；AUTO 改为原生控件。
- 同一静态页面两次复制的底层 ImageReader 缓冲未更新，而复制帧时间变新。静态页面可合法无新帧；复制时刻不能当新内容证据。新 AUTO 不复制图片或伪造新帧，后端只签发真实焦点核验后的操作票据，随后 fresh 读取节点。
- 页面切换的瞬时空焦点以前会立即停止；新 `launch/tap/back` 后允许最多1500ms共享命令预算等待同账号微信恢复，明确错误账号/应用或主屏不安全立即停止。
- 首次读取仅作为最多4秒的稳定比较基线，第二次实际操作票据仍严格1秒、单次消费；不因两次真实焦点查询成本而要求两张票据同时未过期。停止、换模式、过期及新观察均使旧票据失效。
- 独立审查发现并补测：系统边缘栏误拒绝、覆盖控件的窗口、普通聊天结果词误报到账、节点逐次重复RPC、取消/超时后继续遍历、高分辨率面积上限不一致。已知红包接收/详情 Activity 分类仅使用本地微信DEX确证类，未知版本不假报到账。

### 日志与回归证据

原文、控件树、截图及 PendingIntent 不写日志、不落盘、不上传。内存诊断区分通知到达/解析/账号/范围、后端状态、控件窗口/节点数量、标题/卡片/拆开/结果是否存在、引擎阶段、固定失败原因和耗时。`nodes_unavailable` 与普通结果未确认分开显示；新任务清除上一任务页面证据。

真实 RED 记录：`/tmp/shurufa-native-backend-red2.log`、`/tmp/shurufa-native-sdk-red.log`、`/tmp/shurufa-native-routing-red.log`、`/tmp/shurufa-native-read-cost-red.log`、`/tmp/shurufa-native-review-red.log`、`/tmp/shurufa-native-proof-red.log`、`/tmp/shurufa-native-window-race-red.log`、`/tmp/shurufa-native-label-red.log`。旧源码相关检查中的6个 Runtime fake 仍只实现旧 `start`，已按实际新入口更新为 `startForNotification`，未改变并行/路由/白名单行为断言。

最终整合记录：`/tmp/shurufa-native-delivery.log`，Gradle退出码0，`BUILD SUCCESSFUL in 28s`。后端52项、SDK相关485项，共537项，失败/错误/跳过均0。范围为红包模块、`Game*Test`、`ImageUploadScheduleTest`、`InputPriorityWiringTest`，不代表项目全量测试或Lint清洁。独立交叉审查的问题已修正并补回归；`git diff --check`通过。

最终APK：[`shurufa-2026-10-10-v20261010.18-2026101018-debug-cddb5b32.apk`](../../apk/shurufa-2026-10-10-v20261010.18-2026101018-debug-cddb5b32.apk)，包名`com.yuyan.pinyin.offline.debug`，版本`20261010.18`/`2026101018`。SHA256：`cddb5b327186882a7bd4be7b87fc81cab8ea4dba38663ac3e953377ddafc7865`。原证书SHA256：`a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`；交付构建API23/28/36签名校验及独立apksigner校验通过（v1/v2/v3）。本版未安装到手机，未验证真实领取；本节之前的安装/真机记录均属于旧版。

历史 `.15` 状态：2026-10-10 原生尺寸修复包20261010.15/a614d4eb已覆盖安装。已定位固定480×800@160副屏在本机无法完整识别微信页面；原生1200×2664@520私有副屏已完成指定群的非资金导航核验。首次INFO前的单帧标题波动和冷OCR过期改为有限重新核验，INFO提交后仍严格停止。420项相关回归及原签名完整构建通过。真实AUTO领取尚未取得到账证据，不能把非资金自检写成领取完成。

## 实现范围

正式能力已接入输入法，不再依赖单独诊断 APK：Shizuku shell UserService 创建微信私有副屏，通知 PendingIntent 定向副屏，端侧 OCR 双帧识别、群资料验证、红包卡片与打开按钮一次点击、结果复核、停止回收。

默认 OFF；PROBE 只读识别，AUTO 才领取。新安装默认按每条通知的真实 Android 用户区分主微信和分身，并自动核验通知对应的群聊，无需手填群名；完整UID、群详情及双帧验证仍保留。已有账号/群名单配置维持原范围，只有用户显式勾选自动项才扩大范围。仍可取消自动项，选择指定账号和群名单。静默模式启用会关闭原主屏助手，不回退主屏领取。

当前副屏能力仅开放 Android 15/API35 以上，其他系统仍可安装和使用原输入法。需用户启动并授权 Shizuku shell 模式、通知使用权、无障碍服务；重启后重新启动 Shizuku。系统或微信不兼容时停止，不宣称所有手机已验收。

## 保护与资源边界

- 通知最多4条内存暂存；未知前台等保护中断时，未提交通知按原始时间最长24小时暂存，期限不保证红包有效。已核验前台恢复后沿用稳定恢复门。
- 用户2026-10-10最终修订取代此前操作暂停规则：普通应用和游戏前台都允许独立副屏尝试，点击、滚动、触摸和文本变化不取消实际任务，也不等待停手。前台身份未经核验、锁屏/熄屏、主屏使用接收方微信账号仍停止；断线、显式停止和配置变化清队列。原采集上传与主屏旧助手的游戏保护不变。没有待办不轮询。
- 点击卡片前持久保存通知散列ID，提交后即使结果未知也不自动重放；提交前未知前台保护中断的通知可继续有限暂存。落盘和远端点击并非原子操作，极窄竞态下保守跳过。
- 后端要求真实客户端 UID、shell UID2000、固定微信包/所选用户、主屏输入焦点0与副屏微信身份；主屏微信只允许另一已解析Android用户，同目标用户或未知用户仍拒绝，启动前后主屏组件和用户保持一致；帧号单调且仅最近一次未消费帧允许点击，TTL1秒。操作后须有新渲染再取图；静态画面可正常复制最新内存帧。
- 图像仅内存处理并回收。副屏 Accessibility 事件在原采集入口直接隔离，不进入主屏采集、上传、游戏判断。没有任意 shell 命令或主屏点击 fallback。
- 识别Runner设17秒截止；只读诊断17秒、实际任务25秒另由主线程独立定时请求停止副屏，实际任务的截帧/点击/卡片提交还核验同一总截止。不可取消的OCR仍须等回调后回收图片，不能宣称协程会按时硬终止；同步Binder调用有25秒等待限制。服务独立5分钟截止，停止/销毁3秒熔断。绑定在主线程发起，服务业务 RPC 与图像识别在后台执行；取消前尚未开始的协程也有完成回调，不堵后续任务。

## 本地验证

最新并行版：`/tmp/shurufa-concurrent-final.log`，构建退出码0、BUILD SUCCESSFUL；SDK相关322项+后端22项=344项，失败/错误/跳过均0。覆盖真实Runtime主屏交互与游戏并行、未知前台拒绝、停止、原来源/期限、页面固定事实、采集游戏保护及输入优先级行为。APK原证书API23/28/36核验通过。独立审查发现的空package窗口事件沿用已核验状态问题已用真实Monitor事件RED/GREEN修复，最终无剩余Critical/Important。

验证范围说明：较宽一次检查发现现有 `ImageUploadInputHookTest.hardware activity is marked before key dispatch without content` 源码首句断言失败：当前 `ImeService.onKeyDown` 先调用已有 `HumanInteractionRuntime.hardwareKey`，随后才调用 `ImageUploadRuntime.noteKeyActivity`。本轮未改该代码或放宽断言；最终相关行为测试通过，不宣称项目全量测试清洁。

以下为历史版本结果：

最新操作暂停版：`/tmp/shurufa-interaction-pause-final.log`，BUILD SUCCESSFUL；SDK红包256项+后端22项=278项，失败/错误/跳过均为0。10项新增回归分别验证四类主屏操作暂停、3秒稳定恢复、原时效/来源、提交后不重排、显式停止，以及等待窗口内新到通知。原签名构建与独立审查通过。

2026-10-10 设置简化版完整构建：`/tmp/shurufa-simple-setup-green.log`，BUILD SUCCESSFUL。SDK红包246项 + 后端22项 = 268项，失败/错误/跳过均为0；原签名APK构建通过，`git diff --check`通过。本轮新增27项覆盖自动范围兼容、通知来源、真实Runtime逐通知路由、准备步骤和设置页。独立离线代码审查未发现本轮新增严重或重要问题；审查确认完整UID认证、旧手动范围保留、群详情/双帧与截止保护未放宽。以下保留上一轮241项验证与静态检查记录。

- SDK 红包模块219项测试，0失败/错误/跳过；其中新增静默相关76项（Engine18、Reader9、Dispatch4、Settings6、API23页面1、Diagnostics13、UserAction4、RuntimeStop3、RuntimeDiagnosticQuiet4、StartGate3、ReadDiagnostic7、RuntimeActualTimeout4），另新增通知标题回退5项。
- 新后端22项测试，0失败/错误/跳过；覆盖UID、用户、焦点、帧号/TTL/一次消费、新渲染与截止等。
- `redpacket-silent:lintDebug`：0错误、1条 PrivateApi 反射兼容性警告。
- SDK整体验证 `lintOfflineDebug` 执行完成，但旧代码仍有36条错误；新增 Silent 文件没有 Lint 错误，仅应用 Context 静态持有提示。不能据此称整个项目 Lint 清洁。
- 独立审查修复：断线输入轮询顺序、通知重连旧开关依赖、副屏事件隔离、停止竞态、取消队列卡死、输入等待回调累积。后端与识别链最终独立审查无 Critical/Important。新增线程安全内存诊断，独立保留通知门禁、任务结果及运行边界，前台保护不覆盖拒绝原因；仅记录固定枚举、计数及三个独立用户ID，不保存群名/正文/图片。空白 conversationTitle 现在回退有效 EXTRA_TITLE，未放宽账号/群聊/正文校验。

## APK

最新并行版：`apk/shurufa-2026-10-10-v20261010.08-2026101008-debug-84396200.apk`

- 包名：`com.yuyan.pinyin.offline.debug`
- versionName：`20261010.08`；versionCode：`2026101008`（构建按小时生成，与上一包同版本，通过SHA区分）。
- APK SHA256：`843962009519fd6634e2be45e87500f4a6cc61971513e55a21d8dc495175f54e`
- 原证书 SHA256：`a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`
- 构建自动签名验证覆盖 API23/28/36。当前手机断开，最新包尚未覆盖安装。手机最后已安装的是操作暂停版de5c9042，不能据此判断新的并行策略真机效果。保持原AUTO/账号/群范围，不自动重放历史通知。
- 入口：输入法设置 → 其他 → 静默抢红包（Shizuku）。

## 上一轮离线交付边界（e970d5ef）

用户已收回手机，先前等待手机暂停或发红包的请求全部撤销。本轮仅本地开发、测试和打包。设置页新增官方Shizuku下载、首次配对说明、准备项检查和系统设置入口；默认显示简明状态，技术诊断可展开。Shizuku仍须单独安装，首次配对/授权和重启后启动无法由输入法省略。当前能力仍仅API35+，微信不发通知时不触发。

**真实领取仍未通过；268项离线测试与APK构建成功不代表真机可用或资金已到账。** 下文是历史现场证据，不是本轮操作。

## 真机验证边界

荣耀200 Android16，用户已重新开启无障碍服务。Shizuku 服务连接成功，动态账号列表真实返回机主0、分身128，并跳过受限空间100。原主屏红包开关已关闭；产品默认OFF。本次用户授权唯一群“我自己的”的AUTO验收。

微信分身只读副屏自检已通过：动态选定分身用户128，启动微信副屏28，主屏保持原输入法设置窗口且输入焦点0，成功取得内存Bitmap。结束后系统仅剩原有显示0/2，微信副屏28已回收。验证只保存了一张本输入法设置页截图用于阅读状态，随后删除；没有保存微信画面。

自检未发送通知入口、未点击红包、未验证真实到账。

第一次新红包验收未观察到副屏启动或点击，系统当时无微信活动通知记录。用户随后说明红包由分身发送，群成员只有主微信和分身；当时监听用户128是发送方，不是接收方，且主屏正在微信视频号。没有将该次测试计为领取成功，也未重放旧红包。

已将配置保存并核对为用户0（主微信）、AUTO、唯一白名单“我自己的”、原主屏助手false。新版安装后主微信只读自检通过：副屏29已取得内存画面，主屏焦点0，副屏结束后释放。随后回到桌面，并请用户从其他设备登录分身对应账号发送新红包。

第二次用户回复“已发送”后，读取正式设置页的内存诊断：NOTICE_SEEN=0、最近通知NONE、最近任务NONE，没有启动或点击任务。系统未保留微信活动通知。主微信POST_NOTIFICATIONS已授权、AppOps默认allow，新消息渠道importance=3；输入法通知监听在系统已连接列表且服务进程仍在。当前尚不能确认微信未发通知的具体原因，已向用户核对目标群免打扰与电脑端手机通知设置；不能将此次测试计为领取成功。读取状态采用保留无障碍服务的自target Instrumentation，没有读取或保存微信聊天画面。

未提交、未推送、未切换分支；此前诊断和其他现有改动保留。

2026-10-10 00:45 实测新增证据：关闭群免打扰后，收到1条主微信红包通知；PARSED/PROFILE_MATCHED/ELIGIBLE/QUEUED均为1，三种来源user均为0。启动被MAIN_WECHAT_IN_USE拒绝；系统元数据确认主屏为用户128的分身微信LauncherUI，未出现红包副屏。已定位为按包名统一拦截两个Android用户的保护过严，已按上述跨用户隔离修正并完成201项相关测试、原签名构建和覆盖安装。另补主屏VIEW_CLICKED/SCROLLED中断；普通无障碍事件不能证明每个原始物理触摸均可检测，不将此单测称为原始触摸真机验收。

跨用户修正版后端增加5项回归：另一已解析用户微信可留主屏并持续验证；同目标/未知主屏微信拒绝；启动期间主屏用户变化拒绝；运行期间同目标/未知接管拒绝；topDisplay变化拒绝。UserServiceArgs版本从1升到2，安装连接后shell子服务PID由19205变27799、状态IDLE且userId=-1，证实已替换旧实例。目标用户0/AUTO/唯一群白名单及原助手false再次核对保存。当前请用户在分身新发红包后停手等待，尚未把此修正记为真实领取成功。

后续新包首次真正创建副屏30，主屏仍为分身微信且input焦点0；任务被操作保护取消，最后一次重试报告MAIN_FOCUS_UNKNOWN，未领取。已修复显式stop后active尚未清理被前台保护重新入队的竞态；纯游戏保护仍保留未提交任务，提交后不保留。诊断拆分后端启动拒绝与客户端启动后保护，并保存32条固定枚举顺序（界面末16条），区分CLICK/SCROLL/TOUCH/TEXT，原响应只按固定状态白名单保存。详细诊断仅Debug展示。

不发新红包的真机复现：只读自检按钮自身迟到TYPE_VIEW_CLICKED令刚启动副屏31被INPUT_STOPPED_CLICK中止。现在通知入队或主屏操作刷新600ms启动稳定门；等待期间不创建job/副屏，活动job的操作停止不放宽；只读自检等待总上限2秒，显式stop移除pending并以generation防止过期启动。真实Runtime回归先RED再GREEN。最终212项相关测试全部通过；新版安装后重复同一只读动作，副屏32取得内存画面并释放、主屏焦点0、INPUT_STOPPED为0，自检通过。当前仍未确认真实领取成功，已请用户发送新1分钱红包验证。

01:08 新包创建副屏33，主屏分身微信、input焦点0。仅一次TASK_STARTED，RUNNING后约2.5秒TASK_UNKNOWN；本轮无INPUT_STOPPED，也未重入队。01:11只读刷新后端STOPPED/failure=NONE；未生成silent_packet_receipts.xml，未到红包卡片reserve。尚未确认领取，当前补充取帧/OCR/双帧校验固定枚举诊断，不自动重放该通知。

01:22 无新红包只读复现副屏34：首次OCR1461ms、捕获至识别结束2275ms→FRAME_STALE；第二次OCR202ms、帧龄882ms→VALID。主屏始终本输入法设置，焦点0，副屏已释放；仅临时读取本输入法状态页图片，读完删除，无微信图落盘。已据此实现64×64程序空白图OCR预热和原子captureFrame Bundle，保留捕获前后安全验证及1秒TTL；客户端使用真实像素复制uptime，不追加status查询。237相关测试已通过（SDK215+后端22），诊断副屏独立17秒请求停止；实际任务总截止补充中。尚未将此只读修复计为真实领取成功。

01:35 最终整合：实际任务增加25秒独立请求停止及全路径deadline门禁，覆盖start/launch/prewarm/run；4项真实Runtime回归先RED（3失败）后GREEN。原子截图后端22项+SDK红包219项=241项通过，原证书完整APK覆盖安装。UserServiceArgs版本升3，需连接替换旧后端；预热+原子截图真机只读复测进行中，尚未确认真实领取成功。

最终包25ce486f已覆盖安装，配置核对仍为主微信0/AUTO/唯一群我自己的/旧助手false。复测前主屏持续回到用户0微信视频号，同目标微信按规则拒绝，尚未在新版执行预热+原子截帧只读复测，也未连接确认version3子服务替换。已请求手机暂停20秒，待用户空闲继续，不要求重发红包、不自动重放旧通知。最终241测试与构建均为正常仓库源集（未使用临时focused init）；另一个并行任务的中间源码/缓存冲突已经恢复，不改其源码。

## 2026-10-10 用户重新连接后的现场排查

用户安装新版并重新授权连接手机后，恢复仅针对红包故障的设备排查。已安装版本为20261010.07/code2026101007；设置AUTO、auto_user=true、auto_groups=true，通知与无障碍服务连接，Shizuku shell服务及silent_packet进程存在。本轮没有重配账号/群范围、没有发送红包或重放历史通知。

输入法自身设置页内存诊断显示：NOTICE_SEEN=3、PARSED=1、PROFILE_MATCHED=1、ELIGIBLE=1、QUEUED=1、TASK_STARTED=1；后端RUNNING。07:52:19对应顺序为TASK_STARTED → INPUT_STOPPED_SCROLL → BACKEND_START_REPORTED[RUNNING] → TASK_START_INVALIDATED，未进入识别引擎。用户确认当时有滑动、切应用或拉通知栏。因此该次未领取直接原因是现有操作即取消策略，不能归因于群名、通知未到或Shizuku未启动；其他两条通知PARSE_REJECTED不代表该红包解析失败。

用户解锁后从输入法设置执行一次只读自检：副屏36，主屏焦点0；预热339ms；OCR1 VALID/406ms/帧龄632ms，OCR2 VALID/176ms/帧龄402ms；副屏已释放。没有点击红包、没有持久保存微信画面，未验证真实领取。向用户确认是否将点击前操作改为暂停、稳定后继续；未收到选择前不修改既有实际操作取消规则。

### 操作暂停修正版验证

用户随后明确选择“改成暂停后自动继续”。实现3秒主屏操作稳定门、原时间/来源暂存、队列最多4条及显式停止清队列；暂停窗口内新到通知也适用。`deferredByGame`沿用旧字段名，扩展为保护暂停语义；未新增手机配置项。

- 第一轮真实Runtime回归8项先RED（5失败），实现后这8项通过。
- 入队窗口边界2项先RED（1失败），再修正通知在现有暂停窗口内到达的暂存标记。
- 最终正常源集SDK红包256项 + 后端22项 = 278项，失败/错误/跳过均0；完整OfflineDebug BUILD SUCCESSFUL，日志 `/tmp/shurufa-interaction-pause-final.log`。旧Runtime测试隔离同时清理两个稳定门，不改变旧用例预期。
- 独立代码审查未发现新增严重或重要问题，确认ledger防提交竞态、停止串行、原profile、时效和4条上限保留；git diff --check通过。
- 08:08覆盖安装20261010.08，未重放历史通知。真实新红包验证尚待用户发送，不以测试/只读自检替代资金到账验收。


### 最终并行修订与群确认阻塞

用户明确要求主屏使用其他应用时也抢，并最终允许游戏中尝试；已移除实际任务的主屏输入空闲和游戏暂停依赖，独立使用已核验真实前台状态。未知窗口身份立即撤销核验；覆盖null/空package事件。只读诊断按钮保留600ms迟到事件隔离，实际通知不受连续主屏动作影响。保留锁屏、权限、后端焦点0/副屏身份、同目标微信冲突和ledger防重放。

本轮读取已安装操作暂停版的真实诊断：NOTICE_SEEN=16、PARSED/PROFILE_MATCHED/ELIGIBLE/QUEUED/TASK_STARTED/TASK_UNKNOWN各1。TASK_STARTED→BACKEND_START_REPORTED[RUNNING]→TASK_UNKNOWN，没有本次INPUT_STOPPED；GROUP_CHECK在多次有效截帧/OCR后于12298ms TIMEOUT，没有INFO_CLICK或CARD_CLICK。直接阻塞为聊天标题或资料入口未确认，尚未证实具体视觉缺失条件，不再归因主屏操作或通知未到。

新增只读诊断仅输出固定布尔与计数：页面标题、群提示、资料目标、群资料验证、红包面板、可领卡数，以及标题候选/合法三点组/人数后缀/底部微信导航/橙色区域数量。不输出OCR原文、群名、坐标或图片，不改变识别资格、双帧签名或点击条件。未延长识别超时、放宽群验证或重放该红包。手机断开后尚不能读取新事实，因此GROUP_CHECK故障仍未修通，真实领取和游戏前台稳定性均待验证；无需继续反复发红包来替代定位。


## 2026-10-10 用户连接新版后的只读自测

用户明确要求先由开发者自测，确认就绪后再发红包。本轮ADB设备AQUL024807002303已连接；手机实际安装20261010.11/code2026101011，APK SHA256 `7340f0219cbac7835a0d4ca506356aad2ef707d0a839e2ee9d559f99c2b91c66`，不是上文84396200包，未盲目降级覆盖。设置页已包含普通应用/游戏并行说明和source facts。原AUTO、auto_user=true、auto_groups=true、手动user0及旧群范围均保留。

系统最初 enabled_accessibility_services=null、Bound services为空；用户随后重新开启后，FOREGROUND_READY正常出现，设置页准备项齐全，Shizuku连接成功。临时自target辅助工具增加interactive-window检索以读取本输入法自身设置页，不压制无障碍；私有微信副屏未向UiAutomation暴露窗口，尝试只读取固定标签未得到信息，没有输出聊天文字或截图。

只读副屏38/39/40/41均成功取两帧并回收，主屏焦点0；两帧VALID（帧龄315–656ms）。固定事实一致：titleCandidateCount=1、menuDotCount=0、groupSuffixCount=0、bottomWechatTabsPresent=false、orangeRegionCount=0，其余page事实false。活动元数据确认副屏40为user0 LauncherUI。只读start并未执行通知PendingIntent，因此这些事实不能当成此前GROUP_CHECK通知现场或断言登录/聊天状态。

副屏42识别过程中，辅助工具仅对本输入法ScrollView执行一次主屏SCROLL_FORWARD，回报own_scroll_performed=true；两帧仍VALID（404/343ms），副屏正常回收。此结果验证一次真实主屏滚动未取消只读识别，不等同切应用/游戏性能验收或真实领取。尚未点击红包、未重放旧通知、未要求发新红包。下一步补固定提示标签诊断区分登录/加载/网络/权限与群页面，不放宽parser或点击资格。


### 固定页面事实和真实通知入口诊断（本轮续）

副屏43–46增加固定登录/加载/网络/权限提示、正文区域行数、微信标题标签及18类固定关键词枚举；不输出识别原文。副屏45首帧FRAME_STALE不输出页面事实，第二帧VALID；副屏46两帧VALID（604/385ms），识别8行、正文区域6行、标题候选1，其余页面提示均false、fixedKeywords为空。第二次采样延后3秒，仍维持每帧1秒TTL及17秒独立截止；采样时间标签准确表示“预热开始后”。这些结果仅来自LauncherUI入口，不能证明真实通知群页状态或登录正常。

新增仅DEBUG、Android15以上的一次性60秒普通群通知只读检查：使用原已保存账号/精确群范围，完整通知UID与入口creatorUID一致、Activity入口、新鲜15秒且非已识别红包；不构造红包任务、不执行Engine/tap/back、不重放历史通知。等待期间不创建副屏、不轮询、不自动领取；停止/未知前台/断线撤销等待。异步入口贯穿原generation，实际launch前再次检查原通知15秒时效及60秒等待截止，识别仍受原17秒截止。

新通知筛选与生命周期用例16项；取消世代和过期入口两项在临时去掉对应保护时均失败，恢复后通过。最终日志 `/tmp/shurufa-notice-final-green.log`：正常源集SDK350项+后端22项=372项，失败/错误/跳过均0，完整OfflineDebug BUILD SUCCESSFUL。原证书API23/28/36核验通过，git diff --check通过。覆盖安装 `apk/shurufa-2026-10-10-v20261010.12-2026101012-debug-384eeb38.apk`，SHA256 `384eeb389a611126a502e731bc7d6a5c130bf580c387637b71239ffd1d7c6e77`。未修改用户模式与范围，未发消息或点击历史红包；这不是GROUP_CHECK故障已修复或到账成功的证据。

新版真机先完成等待→显式停止（固定日志“用户停止”）；再执行原微信入口只读自检，副屏47两帧VALID（773/404ms），主屏焦点0、finally确认副屏释放，仍缺少群页事实。随后已手动预约下一条新普通群文字通知只读诊断并请求普通消息；尚未收到真实入口验证结果，未要求红包。USB测试保持亮屏临时设置已由2恢复原值0。


### 本轮普通文字通知与等待取消修复

用户确认发送普通文字前后，NOTICE_SEEN从14变为16，两次均进入普通解析PARSE_REJECTED、任务NONE；此前约14ms的主屏前台重新核验已清除只读等待。确认这是只读诊断等待取消问题，不将普通文字的PARSE_REJECTED误记为红包解析故障。未运行该消息的PI或群页面采样，未重放。

仅修改未启动资源的DEBUG普通通知等待：未知前台暂停接收，verified恢复后继续同一armedAt/原60秒期限；未知期间通知不缓存或重放。显式stop同步cancelled，防迟到ready刷新generation复活；息屏和无障碍断连通过Monitor独立取消入口，不依赖verified=false边沿去重，API35以下不初始化新Runtime。真实领取与已开始的只读任务原保护、PI15秒时效、群验证均不变。

新增/更新生命周期回归先RED（12项2失败，独立生命周期13项1失败），实现后最终日志 `/tmp/shurufa-notice-lifecycle-final.log`：SDK355+后端22=377项，失败/错误/跳过均0，完整原签名OfflineDebug BUILD SUCCESSFUL，证书API23/28/36通过。APK `apk/shurufa-2026-10-10-v20261010.12-2026101012-debug-61082eff.apk`，SHA256 `61082eff016f77f6263ab535a4b2405cbcf6f9ef62e694a88974ee92f5c83b23` 已覆盖安装。

手机自测无需消息：手动预约等待→打开系统设置→回本输入法设置，出现两次PROTECTED/READY且仍显示“前台已确认，继续等待普通群通知；原等待期限不变”，backend未启动、任务NONE；随后手动停止。原AUTO/账号/范围未改，USB保持亮屏仍为原值0。此结果仅验证等待修复，不代表真实群确认或自动到账已修复。


### 副屏节点读取的隔离研究与清理

AOSP Android16 `android16-release` commit `99b01a65cc4c104933788b3143285ab6bae65827` 的 [AccessibilityManagerService.isValidDisplay](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#5641) 全局排除非SystemUI所有的private virtual display，与观察者是否同shell UID无关；因此不实现“同UID UiAutomation必定能读private副屏”的错误假设。[PUBLIC flags说明](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/hardware/display/DisplayManager.java#219) 明确PUBLIC改变全系统可发现性，OWN_CONTENT_ONLY不能保留private隔离，不能直接用于正式AUTO。

独立临时app_process探针位于 `.runtime/redpacket-accessible-probe-20261010/`，只加PUBLIC实验位，原安全门禁/15秒独立截止/释放保留，无PI/tap/back/截图/OCR/发送。实验前通过自身设置页暂存AUTO并保存OFF，范围及自动项未改。副屏48启动、持续报告mainIdentitySame=true/topDisplay=0/WECHAT_LAUNCHER；正常退出并请求释放。观察者启动过晚，没有得到节点计数，不能据此判断PUBLIC可读或不可读。再次独立启动时手机已Dozing，返回PROBE_MAIN_UNSAFE并退出，没有继续操作微信。

未要求重发普通消息或红包。配置收尾已完成：用原签名恢复工具仅将原OFF临时值恢复AUTO（其他字段一致才写入），持久值确认AUTO/user0/我自己的/auto_user=true/auto_groups=true。target instrumentation短暂中断了无障碍绑定，随后按原已授权组件重新绑定，系统确认Bound services包含妙言输入法、Crashed services为空、Enabled列表与原相同。设备实验jar与临时恢复APK已删除，DisplayDeviceInfo中已无YuyanSilentPacket，USB保持亮屏为原0；正式APK仍为61082eff，正式PRIVATE flags未改变。锁屏后后续群页检查仍待解锁，未把隔离实验计为到账验收。

### 连接设备后的无资金自检与自动任务日志补全

本轮用户要求开发者完成测试、避免反复发红包。USB连接时临时保持亮屏，原值0留待收尾恢复。只使用本输入法设置页和独立只读副屏，不发送消息、不点击历史红包、不重放历史通知。

隔离PUBLIC实验本轮返回PROBE_START_SECONDARY_UNVERIFIED，已finally释放；不能据此替换正式PRIVATE显示路线。临时OFF经自身设置页恢复AUTO并重新连接Shizuku。正式微信入口只读副屏50返回两帧VALID，主屏焦点0，预热609ms，OCR281/191ms，帧龄501/444ms；两帧均titleCandidateCount=1、menuDotCount=0、groupSuffixCount=0，其余群页事实false，副屏已释放。该检查未执行通知PI，仍不能代替真实红包群页或到账验收。

定位到原日志缺口：真实AUTO任务只保留通用GROUP_CHECK和最近32条阶段记录，重复截帧会挤掉等待原因；普通群通知只读筛选返回null也缺少固定拒绝原因。本轮补固定枚举等待原因与独立最近等待记录，后续有效帧只保留布尔/计数页面事实；普通通知诊断增加有限条数的原因日志。不记录群名、聊天正文、OCR原文或图片，不新增点击资格。

完成范围与验证：新增8项等待原因、2项等待历史保留、3项有效帧发布、2项页面事实保留、3项通知日志、5项主题兼容测试。三轮RED分别为26项8失败、26项5失败、51项5失败，失败均对应新增行为；最终日志`/tmp/shurufa-complete-diagnostics-final.log`：SDK378+后端22=400项，失败/错误/跳过均0，完整OfflineDebug构建成功，原证书API23/28/36核验通过。

深色工具栏浅色三点在合成图上确定漏检，已用ROI不透明像素背景亮度中位数选择前景极性，加90亮度差；恰好三点、中性色、大小/间距/等高/标题对齐约束保留。此兼容修复不是已定位真机GROUP_CHECK根因的证明。正式AUTO日志增加固定AUTO_WAIT与AUTO_FRAME，最多原48帧任务上限；诊断中最近等待及最近VALID页面独立保留，避免被通用截帧记录挤掉。普通消息只读诊断每进程最多64条固定reason/count。

覆盖安装`apk/shurufa-2026-10-10-v20261010.14-2026101014-debug-54f32b18.apk`，SHA256 `54f32b18b5f8e02ab4c6035f09d9705f6b5c0e8c65b1ff9c6084c87d7a1496de`。重新连接Shizuku后，副屏51只读两帧VALID，主屏焦点0，OCR231/196ms、帧龄451/446ms、正常释放；仍是普通LauncherUI入口，无通知PI，titleCandidate1/menuDot0/groupSuffix0，与此前一致。没有发消息或红包，没有点击历史红包。真实AUTO通知群页识别、到账、游戏性能仍未验收，不能宣称功能已做完。

独立审查Engine/Diagnostics/Runtime/Notice日志未发现新增严重或重要问题；主题更改已由根代理核对几何与群验证条件不变。收尾确认原AUTO/user0/旧手动群值/自动账号与自动群开关均保留，无障碍Bound且Crashed为空；实验jar删除、无YuyanSilentPacket残留显示，USB保持亮屏恢复原0。git diff --check通过。本轮未提交或推送，未修改其他功能。


## 2026-10-10 原生显示配置与首次核验稳定性修复

- 最新实际AUTO失败在GROUP_CHECK：一帧标题未确认，随后识别到不等于通知群名的标题便停止；旧诊断误记GROUP_UNVERIFIED。新增固定群名比较事实仅帮助区分差异，不接受近似群名、不输出原名。
- 主屏只读基线与PRIVATE副屏对照：主屏微信列表约39条OCR；480×800@160只有2–8条、720×1280@240约15–16条，原生1200×2664@520达到38–40条且能精确匹配目标群。不能把小画面的空标题直接归因于通知路由。
- 正式ShellDisplay改为读取DEFAULT_DISPLAY realMetrics，副屏采用竖屏排列的原生像素和原生密度；同一不可变profile供VirtualDisplay、ImageReader、像素复制和点击边界使用。无效或超过8,388,608像素拒绝，不降级主屏；PRIVATE flags保持。横屏游戏仅影响被读取的宽高排列，不修改主屏方向。Shizuku UserService版本升4，避免升级仍复用旧尺寸服务。
- AUTO首次INFO提交前，孤立错名只等待下一有效帧；连续两帧同错误名称停止。恢复精确名称后仍要求原双帧目标确认。INFO确认帧错名时只重建尚未提交的Flow状态，原开始时间不续期；INFO成功后及资金动作后不重放。
- 仅首次INFO前，OCR完成后过期的帧最多丢弃2次并捕新帧，保留每帧1秒TTL、来源及单调frameId校验，过期事实不发布。读入时就已过期、来源错误、第三次过期及INFO后过期仍停止。
- RED证据：引擎38项中8项失败，补充INFO确认错名单项失败；实现后39项引擎回归全通过。profile5项先失败后通过。完整验证日志`/tmp/shurufa-native-profile-final.log`：SDK393项+后端27项=420项，0失败/错误/跳过，BUILD SUCCESSFUL；原证书API23/28/36核验通过，`git diff --check`通过。
- 安装包：`apk/shurufa-2026-10-10-v20261010.15-2026101015-debug-a614d4eb.apk`，SHA256 `a614d4eb71b4cfbedc71ac9d0fb5bef99667d55e3162348782d4cd6cc2d2468d`。
- 非资金导航自检display61：列表唯一精确行→群聊双帧→INFO→群资料双帧→BACK→群聊双帧，`SELFTEST_PRECARD_PASSED`；焦点0及原主屏身份不变，未执行CARD/OPEN/通知重放，截图/OCR仅手机内存。该工具使用正式像素解析但不等同整个AUTO引擎。
- 安装后正式Shizuku后端display63确认1200×2664@520、FLAG_PRIVATE。正式应用只读OCR第一帧1099ms过期拒绝，第二帧591ms有效、列表38条、主屏焦点0，副屏释放；与新增有限冷帧丢弃策略吻合。

- 正式Reader非资金自检display64也已PASS（`/tmp/shurufa-production-precard-native-stable.log`）：列表定位使用受限只读OCR，进入群后预热/聊天/群资料/返回均调用安装APK的正式SilentPacketReader，含卡片footer补识别；聊天有效帧约274–536ms、群资料156–198ms。列表OCR目标框首对出现细微变化时拒绝，第二对严格同框后才进入；INFO及BACK均双帧核验。该工具不会执行CARD/OPEN，也未替代真实通知PI路由/到账验证。

- 正式Reader卡片只读确认display65（`/tmp/shurufa-production-precard-cardfacts.log`）：群资料双帧验证并返回后，最终聊天两帧各识别2张可用卡片，反射调用生产`confirmedPacketTarget`得到2个可确认目标。保留精确群名、来源、签名、位置与时效条件，未执行卡片或拆开动作。该事实证明当前页面可到达资金动作之前的识别门禁，不证明历史卡片仍有效或已到账。
- 收尾恢复原AUTO、auto_user=true、auto_groups=true、既有群/用户字段；临时USB亮屏从2恢复原0，删除手机临时profile jar。无障碍安装后仍Bound且Crashed services为空；正式副屏按需释放，未更改其他模块设置。

## 2026-10-10 标题漏括号后的群资料核验

- 用户明确目标是所有群自动处理，无需手填群名。正式通知分发的 `auto_groups=true` 已忽略旧手动测试群字段；通知群名只用于内部会话核验。
- .15 实际任务已收到并入队，在两帧群标题不匹配后停止，尚未点击 INFO/CARD/OPEN。端侧只读形状检查确认标题包含预期名称前缀、一个数字和右括号，但缺少左括号；全标题及紧框 2/3/4 倍 OCR 均未稳定修正。因此不采用删数字、补括号或名称近似匹配。
- 新路径仅允许稳定唯一工具栏的只读 INFO：群资料完整名称必须与通知精确一致，人数至少 2 且两帧一致；返回后两帧必须恢复 INFO 前的原始标题、菜单签名及位置，之后另取确认帧才允许 CARD。原始识别名称不覆写，原任务期限、来源、时效与防重复点击限制保留。
- 全局顶部候选数不是工具栏歧义数。实际失败帧有 2 个顶部候选、1 个菜单；新增 `uniqueChatToolbar` 只根据菜单与标题几何配对唯一性判断，不能直接要求顶部候选只有 1 个。
- 本轮 RED：群资料解析 8 项中 4 项失败；引擎路径最初 7 项中 5 项失败；工具栏唯一性 6 项中 2 项失败。群资料解析初轮 GREEN 8 项通过，最终整体验证另记。
- 后续检查时 ADB 设备及 mDNS 均为空，不能继续安装或声称新路径真机通过。断开前自检临时保存的 OFF、USB 保持亮屏 2 尚无法恢复为原 AUTO/0；手机临时 jar 也未能删除。独立探针进程已退出，后续连接先恢复这些状态，不把该临时 OFF 当作用户关闭功能。
- 最终验证日志 `/tmp/shurufa-details-fallback-final.log`：SDK 418 项、后端 27 项，共 445 项，失败/错误/跳过均 0；OfflineDebug 完整构建成功，原证书 API23/28/36 核验通过，`git diff --check` 通过。新增核验路径、解析及唯一工具栏经两位独立代理审查，无剩余重要问题。
- 本轮唯一新包：`apk/shurufa-2026-10-10-v20261010.16-2026101016-debug-d44ab6cf.apk`，SHA256 `d44ab6cfdf90ecb661c1ac6aef712f64e2aa6144a9365c08d1283e64cdb4e448`。尚未安装到手机，未进行真实通知路由、领取到账或游戏并发性能验收；不把合成测试中的 claimed 状态当作资金结果。
