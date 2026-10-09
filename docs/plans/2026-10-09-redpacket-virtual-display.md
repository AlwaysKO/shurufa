# 群红包虚拟副屏可行性验证计划

> **跨电脑交接入口：** [2026-10-09-redpacket-handoff.md](2026-10-09-redpacket-handoff.md)。下文按时间保留实验历史；后续已授权Hook路线研究、并指定应用分身测试，不能把开头最初授权范围当作最新要求。尚未实际进行分身Hook测试。

**目标：** 验证荣耀 ELI-AN00 上非 Root 虚拟屏能否隔离主屏交互；不把显示成功当作静默红包已实现。
**授权：** 2026-10-09 用户对不 Root、不修改微信的虚拟副屏实验答复“可以”。未授权取消安全锁屏、微信注入、发消息/红包、安装正式改版或发布。
**架构：** 使用经 SHA256 校验的官方 scrcpy 5.0.1；若标准副屏抢焦点，以一次性 shell 探针验证 Android `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED`。探针复用官方 scrcpy 上下文初始化/显示包装，仅增加临时显示标志，像素不落盘、不上传，限时自动退出。
**技术栈：** ADB、Android16、官方 scrcpy server、Java/D8。探针/日志仅放 Git 忽略的 `artifacts/diagnostics/2026-10-09-virtual-display/`，不改生产代码。

## 步骤与门禁

1. 保存基线：display0 主屏任务/焦点，已有 display2 不动，锁屏状态与两个休眠设置。
2. 标准 scrcpy 创建720×1280/240dpi副屏，仅打开系统设置，关闭音频/剪贴板同步。比对主屏任务及输入焦点；失败则先清理，不打开微信。
3. 针对焦点写设备状态断言；在已保存失败样本上确认 RED。创建限时 shell 探针，唯一显示标志差异是禁止抢顶层焦点；重新比对 GREEN。
4. 仅主屏焦点守卫通过才做指定 displayId 的无副作用输入；真实手指并发触摸须用户配合，不能用脚本状态替代。
5. 隔离成立才验证主微信 user0页面归属及息屏/锁屏差异。真实领取须明确测试群/新红包，未测即标待验收。
6. 释放本次副屏/进程，核对主屏焦点、原有display和设置；写回结论。保留所有既有未提交改动，不自动提交/推送。

## 初步证据

- 官方win64压缩包SHA256：b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5。
- 基线主屏荣耀桌面，screen_off_timeout=1800000，stay_on_while_plugged_in=0，secure=true且当时未锁。
- 标准scrcpy创建display3，设置位于display3，主屏Activity未改变；但主屏mCurrentFocus=null，输入FocusRequests主屏NOT_FOCUSABLE，FocusedDisplayId=3。
- 停止本次进程后display3消失，主屏原窗口焦点恢复，休眠设置不变。尚未打开微信或领取红包。
- AOSP Android16说明OWN_FOCUS与STEAL_TOP_FOCUS_DISABLED分别控制独立焦点和不抢顶层焦点；scrcpy5.0.1未设置后者。推断为待验证原因，不是已证实荣耀实现细节。

来源：
- https://github.com/Genymobile/scrcpy/blob/v5.0.1/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/view/Display.java

## 实测结果（2026-10-09）

### 已验证的范围

1. **标准方案未通过主屏焦点门禁。** `check_focus.py focus-settings.txt` 实际失败：主屏窗口焦点丢失。主屏Activity未切走不等于输入完全隔离。
2. **禁止抢顶层焦点的实验探针通过桌面焦点门禁。** 创建display4，实际显示标志含OWN_FOCUS和STEAL_TOP_FOCUS_DISABLED；设置的窗口在副屏，主屏桌面窗口保有焦点、mTopFocusedDisplayId=0。断言 `check_focus.py focus-isolated.txt` PASS。仅对副屏执行滑动后仍保持。探针使用ImageReader而不是scrcpy视频编码，故这是修改后方案的通过证据，不是严格单变量性能对照，也不能据此归因全部厂商行为。
3. **主微信可在副屏运行。** user0 com.tencent.mm/.ui.LauncherUI 位于display4，主屏原桌面Activity/窗口焦点保持，旧红包探针action_at未更新。没有安装或修改微信，也没有让旧红包助手代替试验领取。
4. **物理息屏且安全锁定时副屏仍可显示及响应定向滑动。** 使用KEYCODE_SLEEP后，物理屏OFF、deviceLocked=1、showing=true、secure=true；副屏ON且微信Activity RESUMED。720×1280副屏截图仅在电脑进程内作像素统计，不写PNG、不记录内容；非黑像素比例亮屏和锁屏后均0.9971，定向副屏滑动前后像素差异非空，帧计数189→303。此证据支持副屏在锁屏期间仍有画面更新，不代表红包交易成功。
5. **没有修改安全锁屏配置。** 探针沿用官方scrcpy的TRUSTED/OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED临时副屏标志，所以物理屏锁定不同时锁住该副屏。这意味着受信ADB会话可以继续操作副屏，不能描述为与普通锁屏相同的隐私隔离；应限定在用户已授权、已解锁初始化的实验会话，不能承诺重启后未首次解锁可运行。
6. **息屏不等于休眠省电。** 物理屏OFF时mWakefulness仍Awake，虚拟屏ON；没有进行电量、温度、CPU或游戏帧率对照，不能宣称无耗电、无卡顿。

### 清理与交付边界

- 停止本次探针进程；复查活动显示ID只剩0，SurfaceFlinger只剩原物理屏，Activity display4已消失，原系统PreloadDisplay2保留。
- 删除手机本次两个带日期的临时JAR；未卸载/清数据，未改权限/锁屏配置。恢复物理屏亮屏但不代替用户解锁，因此结束时主屏为安全锁屏，底层仍原桌面任务。
- screen_off_timeout仍1800000，stay_on_while_plugged_in仍0。
- 电脑便携工具位于 `C:\Users\ES-11013\AppData\Local\Temp\shurufa-vdisplay-20261009`；源探针、诊断统计和断言位于忽略目录 `artifacts/diagnostics/2026-10-09-virtual-display/`，未加入Git。
- 本轮只新增此验证文档及本地诊断文件，无正式红包代码改动、无APK交付、无提交/推送。

### 下一步与未验收

- 待用户指定测试群，准备可控的副屏测试入口后，再由用户/群友发新红包。尚未验证通知路由、群身份核验、卡片/拆开/到账/收尾任何完整交易链路，不让用户现在盲发测试红包。
- 尚未验证前台普通App真实输入、主屏持续双指触摸/游戏、音频焦点、性能、长期锁屏、断连/重启/微信更新。桌面焦点通过不能替代这些验收。
- 当前依赖电脑ADB授权和shell临时进程，不是手机独立静默自动抢。独立运行可能需要另行授权Shizuku等能力，不能把无障碍现有权限当作具备该能力。
- 旧红包实现操作默认屏幕，不能直接挪到副屏；若继续产品化须明确displayId隔离、主屏输入保护、事件与截图归属、通知/点击状态机及失效清理。正式改动另行设计、测试及原签名交付，不捆绑当前其他未提交功能。

## GitHub源码调研与无通知要求（2026-10-09 后续）

用户明确要求检索 xbdcc/GrabRedEnvelope、dengzemiao/DZMRedEnvelopeHelper 及近期相关仓库，并指定两人测试群已有红包。后续澄清“不打开通知”是**关闭微信所有系统消息通知**，不是只关声音或群免打扰。本轮不更改用户通知配置、不安装第三方APK、不运行第三方抢红包脚本；API元数据和源码仅下载到本地忽略目录研究。

### 已核对的仓库（日期为本次查询默认分支最新提交，不把pushed_at或抓取日期当功能更新时间）

| 仓库 / 默认分支提交 | 实际机制与参考价值 | 限制 |
|---|---|---|
| xbdcc/GrabRedEnvelope，3367d2f，2025-10-16；pushed_at另为2026-01-03 | WechatService.kt独立处理通知、列表摘要、聊天页；monitorChat按列表预览红包标记进入，grabRedEnvelope检查可见卡片、openRedEnvelopeAuto有限重试并退出详情 | WechatConstants.kt兜底仍标8.0.45/8.0.47控件ID；本机8.0.78（3180）不能直接套。没有副屏实现。README仅学习、非商用，未识别标准LICENSE，不直接复制进产品 |
| dengzemiao/DZMRedEnvelopeHelper，40ee313，2026-02-13 | README及实际project.json入口main.js显示当前版仅钉钉；仓库src/main.js仍残留旧微信wx_start、卡片状态与拆开流程，可研究页面识别状态 | 当前运行入口已移除微信。旧微信代码依赖硬编码ID和递归轮询，不是当前微信支持证明；README非商用，不移植代码 |
| ven-coder/assists，f95d360，2026-09-29 | 较新通用Android无障碍框架，步骤器、OCR/OpenCV、调试能力可作架构参考 | 不是本机静默红包验证，GPL-3.0，不因框架新就替换现有链路 |
| shuang-afk/wechat-red-packet-cv-helper，7273b48，2026-05-28 | 阅读grab_hongbao.py；Windows屏幕OpenCV模板匹配+PyAutoGUI；提供dry-run、once、超时/冷却、failsafe，可参考只识别不点和失败停止 | PC界面自动化，不是Android后台接口；MIT，不等于适配本机微信 |
| paijipao123/WeChatKit，1a212dd，2026-09-21 | 阅读AutoRedPacketFeature.kt：LSPosed/Xposed监听微信内部消息入库、反射调用微信内部红包流程，不依赖系统通知或模拟页面点击 | README仅微信8.0.76，与本机8.0.78不符；需要进程Hook环境，超出本次不Root、不修改微信边界。仅源码研究，未安装/运行、未调用任何内部资金接口 |
| verygameyth/RedPackage，a770a7c，2024-05-13 | 无障碍加通知/列表，参考普通状态处理 | 较旧、README仅8.0.47适配，Apache-2.0；不当作近期可用方案 |

源码锚点：
- https://github.com/xbdcc/GrabRedEnvelope/blob/3367d2fd491ae9792379337e3112465f2b5876c0/app/src/main/java/com/carlos/grabredenvelope/services/WechatService.kt
- https://github.com/xbdcc/GrabRedEnvelope/blob/3367d2fd491ae9792379337e3112465f2b5876c0/app/src/main/java/com/carlos/grabredenvelope/util/WechatConstants.kt
- https://github.com/dengzemiao/DZMRedEnvelopeHelper/blob/40ee313580e66d225a757e21de3c7e93d876facc/README.md
- https://github.com/dengzemiao/DZMRedEnvelopeHelper/blob/40ee313580e66d225a757e21de3c7e93d876facc/main.js
- https://github.com/dengzemiao/DZMRedEnvelopeHelper/blob/40ee313580e66d225a757e21de3c7e93d876facc/src/main.js
- https://github.com/ven-coder/assists/tree/f95d3607b1a1291799f2587414ecdd06d06c129f
- https://github.com/shuang-afk/wechat-red-packet-cv-helper/blob/7273b48283d8865f50966648367a78d33717b213/grab_hongbao.py
- https://github.com/paijipao123/WeChatKit/blob/1a212dd07f396e8d28e31d70c0798f88b5beffe4/app/src/main/java/simple/hook/wechat/features/money/AutoRedPacketFeature.kt

### 无通知的能力边界

- 通知全关不阻止从已显示的群页面识别卡片；通知监听无法凭空补出未产生的通知。
- 在不Hook微信、不访问私有数据的方案下，覆盖多个群需要页面事件/截图识别与受控轮询相结合。固定群守候和多群轮询应分开验收；不能承诺全群秒抢或零耗电。
- 测试群真实会话列表的最近摘要先是改群名提示、后是普通文字，而进入群后有未领取外观的红包。这直接证明只依赖列表最新摘要中的红包标记会漏掉被后续消息覆盖的红包。不能只加列表扫描就宣称解决无通知全群发现。
- 现有正式GroupRedPacketAssistant只有通知请求或当前可见聊天页启动，尚无副屏和多群轮询；因此无法把关闭通知且其他App前台的目标当作现版已有能力。

## 已有红包副屏测试（15:55–16:00）

### 第一轮：被主屏微信操作打断

- display5试验中列表移动/变化，首次坐标选择进入非目标会话；随后标题核验停止，没有点红包或发送消息，尝试返回。
- 用户确认当时在主屏手动打开微信或点返回。系统之后显示微信返回display0，display5无前台任务，15:56:35记录ANR：Input dispatching timed out，Application does not have a focused window。
- 已停止探针并清理副屏。用户后续主屏恢复桌面。上述相关性不能独自证明系统ANR全部根因，不能归咎用户；正式功能必须在主屏用户使用微信时暂停/交还控制权。

### 第二轮：无通知打开红包，拆开结果未确认

- display6重新运行；先等待列表稳定，再逐帧比对目标行区域后进入用户指定群；确认标题含2人，并查看群资料核对群聊名称、群公告。
- 物理屏进入锁屏（showing=true、secure=true，mTopFocusedDisplayId=0）后，从群页面打开已存在的左侧入站红包，副屏明确显示红包弹层及“開”。本过程由助手观察画面后用ADB向display6定向操作，不依赖通知，也不是正式红包程序自主识别/点击的验收。
- 二次复核按钮区域后发出拆开点击。15:59:52采样主屏已是抖音、物理屏ON、锁屏showing=false；副屏OFF且画面全黑，没有获得到账/已领完/已过期结果。
- 16:00:06/18副屏当前窗口属于com.ss.android.ugc.aweme，而微信红包Activity仍留在display6且暂停。用户随后确认确实解锁并打开抖音。这里证明这次正常主屏使用与副屏异常同时发生，不证明抖音窗口导致息屏的完整因果链；不读取抖音内容、不修改抖音。
- **领取结果未知，不能宣称抢到，也不自动重放拆开。** 后续应先让用户核实该红包是否已到账或在准备好的只读检查中核验；不能因黑屏断言领取失败。
- 主屏操作其他App正是用户要求支持的场景，不能靠要求用户不碰手机作为正式解决方案。

### 收尾与后续门禁

- 已停止本轮两个探针；活动显示仅0，原PreloadDisplay2保留，本次手机两个JAR已删除。screen_off_timeout=1800000、stay_on_while_plugged_in=0未变；无新APK、无提交/推送。
- 本轮真实页面截图仅用于已授权测试、本地忽略目录保存，不上传、不加入Git；研究README以txt留本地。前一阶段“像素不落盘”仅描述前轮统计，此轮实际截图已如实区分。
- 下一步先研究私有/非presentation副屏及主屏接管暂停的隔离效果，阻止无关App向副屏建窗口；这只是由公开presentation显示与抖音窗口现象提出的假设，未实现/验证，不擅改全局显示配置。
- 必须补齐点击前后显示归属/状态/窗口/目标群复核以及结果不明不重放，再做单群无通知完整领取。安全、输入/游戏保护、能耗、脱离电脑运行仍待独立设计验收。

## 黑屏责任澄清与三秒目标（2026-10-09 用户追问）

- **物理手机息屏由助手主动触发。** display4锁屏实验以及display6进入目标群后的实验均由助手执行`input keyevent 223`（KEYCODE_SLEEP）。不是用户主动关屏，也不能笼统归因用户打开抖音。此前表述未清晰区分物理息屏与副屏黑画面，现予以纠正。
- **随后副屏黑画面是另一个现象。** 15:59:52记录为物理屏ON、主屏抖音、副屏6 OFF；副屏探针当时尚运行，未到180秒结束时间，清理在其后。可排除本次按时退出/事后清理直接造成该次黑画面，但无法凭这些记录排除实验显示标志、电源组状态或此前主动息屏的影响；根因未定，不归咎用户。
- 本次追问后的检查只读，不执行按键、点击、唤醒或息屏。17:18:29检查未发现实验副屏/探针残留，超时配置仍1800000、插电保持唤醒仍0。针对15:58–16:00的系统电源/显示标签日志查询未返回匹配行，不能据此补造当时关屏原因。
- 用户说明应用个人自用、不上架；后续讨论重点放在技术可行性，不将商用限制作为主要阻碍。这不自动变更既有不Root、不修改微信的试验边界。
- 用户提出多数群免打扰、系统通知关闭、希望红包发出3秒内领取且低耗电。当前仅作为待讨论目标，不宣称已经实现或可保证。
- 可评估会话列表变化驱动的增量检查：将排序、时间、摘要、未读状态的变化合并为候选群，只检查变化群；缓存检查状态并限制重试。红点已经存在时后续消息不一定改变红点，列表摘要会被覆盖，屏外条目/无障碍空树仍可能漏检。事件是否可靠需要实测，不能把此方案当作全群三秒保证。
- 现有副屏曾使物理息屏时系统仍Awake，尚无功耗验收。内部新消息事件Hook是另一技术路线，不依赖逐群扫界面，但需要另行讨论运行环境与授权、适配本机微信；Shizuku本身不提供微信内部消息。任何路线均不能保证网络、微信服务端及红包余量，因此须区分发出到手机收到、收到到识别、识别到领取结果的延迟。

## Hook路线研究授权与8.0.78初查（2026-10-09）

用户明确要求参考WeChatKit适配本机8.0.78，并研究Hook路线。本轮先做源码/安装包静态研究及设备只读核验；研究授权不等于允许解锁/Root、替换微信、迁移账号或调用内部资金接口。采用先设计后实施流程，无正式实现/安装。

### 具体证据

- 本机复查：Android16，微信8.0.78/versionCode3180；ro.boot.flash.locked=1、vbmeta.device_state=locked、verifiedbootstate=green。shell uid2000，PATH未找到su；主用户0包名筛选未发现常见Magisk/LSPosed/LSPatch/KernelSU/APatch/Shizuku标记。包名筛选可能漏掉隐藏/改名管理器，不当作无Root的绝对证明；未执行su、重启或解锁。
- 只读导出已安装base.apk至电脑临时目录`C:\Users\ES-11013\AppData\Local\Temp\shurufa-vdisplay-20261009\wechat-8.0.78-3180.apk`，未读取微信聊天数据库、未上传APK。大小280614450字节，SHA256 `41f7dc1f720767fa78fa20dd13ea034b817bbf6ebd23dfd1324c647499c9c1ba`。
- 静态DEX字节字符串检查：classes13.dex仍含MicroMsg.NetSceneReceiveLuckyMoney、MicroMsg.NetSceneOpenLuckyMoney；classes11.dex含MicroMsg.NetSceneBase；仍存在WCDB两种SQLiteDatabase描述符及modelbase.u0描述符。此为特征存在证据，**不是类唯一定位、方法签名、回调语义或运行兼容验收**。证据在本地忽略目录research/wechat-8.0.78-static-presence.json。
- 阅读固定SHA 1a212dd的HookEntry、DexKitFinder、WeChatNetwork及AutoRedPacketFeature：可参考消息入库事件+特征定位架构，而不是只替换界面控件ID。还需核验热补丁后ClassLoader、请求构造签名、网络分发方法/回调类型和业务响应。
- 静态审查发现参考代码风险：回调从ConcurrentHashMap.entries.lastOrNull取任务而非对应请求；lastSendId仅记一个值，不能承担并发/重复消息去重；发送返回值未作为成功门禁，未完成领取结果确认就移除任务；固定参数类型/位置及回调错误状态检查不足。这些必须独立修正，不能把README的8.0.76支持当成已验证的可靠基线。

### 运行路线与建议（待用户选择，未实施）

1. Root+Zygisk Hook：JingMatrix/Vector上游说明要求Magisk/KernelSU及Zygisk，支持范围包含Android16；不要求重打包目标APK，但本机未确认有运行条件，不擅自为主力手机解锁/Root。
2. 免Root重打包：原LSPosed/LSPatch已归档，但JingMatrix/LSPatch v0.8发布说明明确支持Android16，并提供Shizuku辅助dex2oat。它仍将加载代码嵌入目标APK，不是Shizuku直接读取消息；签名、安装覆盖、登录/更新与微信自身兼容性均待验证，不能直接覆盖用户主微信或宣称无账号风险。
3. 建议先在隔离测试环境完成只识别不领取的Hook探针：仅验证模块载入、版本特征和新消息事件/延迟，不记录聊天正文；再单独设计领取状态机。优先独立最小模块，不把WeChatKit内的自动收转账、签名绕过、防撤回等无关功能带入输入法。

来源：
- https://github.com/paijipao123/WeChatKit/tree/1a212dd07f396e8d28e31d70c0798f88b5beffe4
- https://github.com/JingMatrix/Vector （README运行要求）
- https://github.com/JingMatrix/LSPatch/releases/tag/v0.8 （Android16支持声明，不是本机验证）
- https://github.com/LSPosed/LSPatch （重打包机制及原仓库归档状态）

补充定向反编译核验：jadx对本机APK的`com.tencent.mm.modelbase.u0`单类导出成功；其声明仍为接口，方法签名`void onSceneEnd(int, int, String, com.tencent.mm.modelbase.m1)`。这一个固定回调类型与参考代码预期相符；其他请求构造、网络分发及热补丁运行行为尚未验证。导出文件`research/wechat-8.0.78-u0.java`仅本地忽略目录保存。
