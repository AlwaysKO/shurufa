# 微信群红包功能：跨电脑开发交接

更新：2026-10-09。本文件是本次会话整理，不是适配完成或领取成功的证明。

## 1. 给下一位开发助手的任务摘要

用户要在自己的输入法项目 shurufa 中实现低额外耗电、静默的微信群红包自动领取：其他应用前台及息屏均可工作，微信所有系统通知关闭，多数群设消息免打扰，希望红包发出后3秒内领取。用户同意研究参考 WeChatKit 的 Hook 路线并适配当前微信8.0.78，不接受只因参考项目标8.0.76就放弃。

用户最新原话：“没有，你可以用的的应用分身微信测，你给我把此会话整理好，我后面会在另外一台电脑上去开发此功能了”。含义：没有备用安卓手机，后续测试对象指定为本机微信应用分身；本轮先交接，没有实际执行分身测试。

**继续开发时先做版本静态适配及分身隔离核验，再设计最小 Hook 探针。不要直接覆盖主微信、刷机或继续副屏抢红包试验。**

## 2. 项目与机器入口

- 当前Windows/WSL项目根：`/home/ko/project/shurufa`。用户要求记住并复用已知路径，不反复全盘搜索。另一台电脑路径未确认，不能照搬。
- 本次交接快照：分支`main`，HEAD `aa8c5326560e92303712df3f58959598f4bfd2a0`。工作区还存在其他任务未提交修改；这不是本功能发布版本，不要覆盖/回滚他人改动。
- 当前项目规则：项目`AGENTS.md`；详细实验记录：`docs/plans/2026-10-09-redpacket-virtual-display.md`。历史游戏保护：`docs/plans/2026-10-04-redpacket-game-protection.md`。
- 跨项目记忆入口：`/home/ko/project/obsidian/04_系统维护/运行与验收/跨项目工作记忆入口.md`；路径复用偏好已在该Vault登记。另一台电脑是否同步Vault未知。
- 正式红包代码：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/redpacket/GroupRedPacketAssistant.kt`。
- 手机：荣耀ELI-AN00，Android16；ADB序列号`AQUL024807002303`。本机ADB：`/mnt/e/AndroidSDK/platform-tools/adb.exe`。
- 微信：包名`com.tencent.mm`，8.0.78，versionCode3180；此前识别主用户0、分身用户128。**重连必须重新核实userId、账号、安装来源及测试群，不硬编码128。**

## 3. 用户要求与授权边界

- 系统通知全部关闭，不只是群免打扰；微信内部仍有未读数字/红点。多数群免打扰，不想全群高频轮询。
- “3秒”指从红包发出到领取，不应偷换成“检测到后点击3秒”；网络、系统调度、红包余量不能保证。后续需分段统计发送→手机收到→识别→请求→结果。
- 个人自用、不上架。讨论重点是可行性，不反复以商用限制阻止研究。
- 已同意参考开源项目、研究Hook并适配8.0.78，最新指定应用分身作为测试对象。
- **分身测试对象授权不自动包含解锁Bootloader、Root、卸载/清除微信、重签覆盖安装、迁移账号、绕过安全锁或调用内部资金接口。**具体方案涉及这些步骤时须先解释影响并明确确认，不将旧的“禁止研究Hook”理解为仍有效，也不将“可以用分身”扩张为任意改机授权。
- 分身不等同独立虚拟机；必须核验是否与主微信共用安装包/签名/更新，以及框架能否按Android用户隔离作用范围。未证明隔离前，不进行可能影响主微信的安装或注入。
- 输入/游戏流畅优先。现有游戏保护规则尚未因新路线讨论而取消；需另行协调Hook策略与该规则，不能默默删除。
- 不发消息或红包，不自动收转账；测试所需新红包由用户准备。既往拆开结果不明的任务不得自动重放。
- 本轮仅整理交接，无手机操作、无正式代码实现、无提交/推送/安装。

## 4. 已有功能为什么漏抢

现有GroupRedPacketAssistant依赖有效系统通知或当前可见群页；没有真正的微信内部消息订阅、虚拟副屏后台服务或全群发现机制。关闭系统通知且其他App前台时，不能当作已有能力。

通知入口有15秒时效、标题/正文及PendingIntent校验；安全锁屏下旧方案受限。当前手机无障碍树经常只有一个无尺寸节点，部分识别依赖截图/OCR。

红点只表明未读，不表明红包；原本已有红点时再来消息可能不改变红点。列表最新摘要还会被后续普通消息覆盖，测试群实测确有该情况。因此“只扫列表红包摘要”不能保证不漏。

## 5. 副屏试验结论与黑屏责任

- 标准scrcpy副屏导致主屏窗口焦点丢失，未通过输入保护。
- 加OWN_FOCUS和STEAL_TOP_FOCUS_DISABLED的临时shell探针通过桌面焦点测试；曾在物理屏锁定时显示微信并响应副屏定向滑动。**不是完整自动领取验收。**
- 当时物理屏OFF而系统mWakefulness仍Awake，不可宣称低耗电；没有功耗或游戏帧率验收。
- 第一轮实际群测试受主屏手动打开微信/返回干扰，出现微信无焦点ANR。用户确认有这些操作；不能归咎用户，正式方案必须支持正常主屏操作。曾误进入非目标会话，标题校验后停止，没有发送消息/点该群红包。
- 第二轮在“我自己的”两人群打开已有红包并显示“開”，发出拆开点击后副屏变黑，**到账、领完、过期结果均未确认，不得称抢到或失败。**后续分身是否有该群/该红包尚未核验。
- **物理手机息屏是助手执行`input keyevent 223`主动造成的，不是用户关屏。**之前混淆物理息屏和副屏黑画面的说明已道歉纠正。
- 15:59:52副屏黑画面发生时，物理主屏ON、用户已打开抖音、副屏6 OFF；探针仍运行、未到180秒退出期限。16:00左右副屏出现抖音窗口，微信红包Activity暂停。根因未定，不把相关性说成抖音导致或用户过错；可能涉及实验显示标志/电源组，尚未证实。
- 已停止实验探针、移除临时副屏和手机2个JAR。后续只读检查未发现残留；screen_off_timeout仍1800000、stay_on_while_plugged_in仍0。不继续主动息屏/唤醒复测。

## 6. 开源研究：哪些值得参考

| 项目 | 本次核对状态/用途 |
|---|---|
| [xbdcc/GrabRedEnvelope](https://github.com/xbdcc/GrabRedEnvelope) | SHA3367d2f；通知+列表摘要+可见聊天页无障碍自动化，控件适配较旧；不是静默内部消息方案 |
| [dengzemiao/DZMRedEnvelopeHelper](https://github.com/dengzemiao/DZMRedEnvelopeHelper) | SHA40ee313；当前实际入口已移除微信、仅钉钉；src/main.js残留旧微信轮询代码，不当作当前支持 |
| [ven-coder/assists](https://github.com/ven-coder/assists) | SHAf95d360，2026-09-29；通用无障碍/OCR框架，不是静默领取已验收 |
| [shuang-afk/wechat-red-packet-cv-helper](https://github.com/shuang-afk/wechat-red-packet-cv-helper) | SHA7273b48；Windows视觉自动化，可参考dry-run、冷却、失败停止，非Android方案 |
| [paijipao123/WeChatKit](https://github.com/paijipao123/WeChatKit) | SHA1a212dd07f396e8d28e31d70c0798f88b5beffe4，2026-09-21；README仅8.0.76；重点研究对象 |

WeChatKit核心思路：监听微信WCDB消息入库→识别红包消息→按DexKit字符串特征定位业务类→经微信内部网络流程接收/打开→处理回调。它不需要逐群扫界面，但必须先有Hook运行环境，不能直接塞进普通输入法APK就跨进程生效。

已读关键文件（相对于WeChatKit仓库）：
- `app/src/main/java/simple/hook/wechat/features/money/AutoRedPacketFeature.kt`
- `app/src/main/java/simple/hook/wechat/core/WeChatNetwork.kt`
- `app/src/main/java/simple/hook/wechat/core/DexKitFinder.kt`
- `app/src/main/java/simple/hook/wechat/HookEntry.kt`
- 构建脚本、Manifest、xposed_init。

参考代码自身需要修正，不仅是改版本：
- 回调用ConcurrentHashMap.entries.lastOrNull选择任务，没有与具体请求关联，存在并发串任务风险。
- lastSendId只记一个值，不足以跨回调/并发/重复入库去重。
- 网络发送返回值未作为成功门禁，缺少可靠的领取最终结果确认，任务移除过早。
- 固定构造参数、回调参数位置、硬编码类型需适配；错误状态、超时、退出及补丁后ClassLoader要核验。
- 不复制自动收转账、签名绕过、平板模式、防撤回等无关功能。优先独立最小红包模块，与输入法主功能解耦。

## 7. 本机8.0.78静态检查：不是从零猜测

只读导出的安装包大小280614450字节，SHA256：
`41f7dc1f720767fa78fa20dd13ea034b817bbf6ebd23dfd1324c647499c9c1ba`

已验证：
- classes13.dex仍有`MicroMsg.NetSceneReceiveLuckyMoney`和`MicroMsg.NetSceneOpenLuckyMoney`字符串。
- classes11.dex仍有`MicroMsg.NetSceneBase`字符串。
- WCDB旧/兼容SQLiteDatabase及modelbase.u0类型描述符存在。
- jadx定向反编译`com.tencent.mm.modelbase.u0`成功，仍为接口：`void onSceneEnd(int, int, String, com.tencent.mm.modelbase.m1)`，与参考代码的这个回调预期相符。

**尚未完成：**红包类唯一定位、请求构造签名、完整网络分发/响应结构、Tinker热补丁下的真实运行类、Hook载入、免打扰/息屏事件实测、领取验证。不能把字符串存在说成已适配。

## 8. Hook运行环境选择

本机Android16；只读属性为flash.locked=1、vbmeta.device_state=locked、verifiedbootstate=green；shell是uid2000，PATH未见su，主用户包名筛选未发现常见框架管理器。隐藏/改名可能漏检，不能据此绝对认定无Root；未执行su或解锁操作。

- **Root路线：**[JingMatrix/Vector](https://github.com/JingMatrix/Vector)上游要求Magisk/KernelSU＋Zygisk，声明支持范围包含Android16；本机没有确认可用环境。不要因为模块适配成功就跳过设备可行性。
- **免Root改包路线：**原LSPosed/LSPatch仓库归档，但[JingMatrix/LSPatch v0.8](https://github.com/JingMatrix/LSPatch/releases/tag/v0.8)声明支持Android16，提供Shizuku辅助dex2oat。它仍嵌入加载代码到目标APK，不等于“不改微信”，框架支持也不等于本机微信兼容。
- Shizuku本身不提供微信内部消息/资金接口。改包的签名、安装覆盖、账号登录/升级、数据保留与分身隔离必须先评估，不建议未经验证直接处理主力账号。

## 9. 下一台电脑的执行顺序

1. 阅读本文件、详细实验记录及本机实际AGENTS；核对当前分支/未提交改动，复用已知路径但按新机器确认。
2. 用户已选分身测试：先只读核对设备、userId、微信版本、代码路径和主/分身关系；确认测试账号与群。不擅自启动主微信、息屏或打开红包。
3. 继续离线静态分析8.0.78，形成可验证的类/签名/回调适配清单。不要通过试错调用领取接口猜参数。
4. 提交可理解的运行环境设计：能否只影响分身、需要哪些安装/改机步骤、如何停用/恢复。隔离不能保证就停在研究阶段说明，不尝试覆盖主微信。
5. 设计并经确认后实现“只识别不领取”最小Hook探针：默认关闭资金动作，只验证载入、特征定位和红包到达事件；不保存聊天正文/完整红包凭据。
6. 再按TDD实现请求与回调一一对应、幂等去重、群范围、专属红包适用性、超时/异常停止、未知结果不重放、最终结果确认；定义与游戏保护的兼容策略。
7. 分别验证普通App前台、分身/主微信切换、免打扰、通知全关、亮屏/息屏、并发红包、更新/重启。记录发现率、端到端延迟分位数、功耗与输入影响，不以一次成功承诺所有场景3秒必抢。

## 10. 迁移材料与缺口

**必带文本：**本文件＋`2026-10-09-redpacket-virtual-display.md`。两份包含主要会话事实、源码锚点、版本与哈希，可直接作为新会话上下文。

**可选本机证据（Git忽略，默认不会随clone迁移）：**
- `artifacts/diagnostics/2026-10-09-virtual-display/`：探针源文件、焦点断言、只读状态、研究源码、静态检查JSON及u0导出。这里还含真实聊天截图，默认不要整目录上传或分享。
- Windows临时目录`C:\Users\ES-11013\AppData\Local\Temp\shurufa-vdisplay-20261009`：便携scrcpy/JAR及微信APK。APK仅本地分析，未修改/上传，不需放入Git；新电脑可从获授权设备重新导出并核对版本/哈希。
- 本次无正式红包实现改动、无新APK交付、无已确认完整领取、无Hook安装。新电脑只拉远端仓库**不保证拿到这两份未提交文档**；须手动复制交接包或另行明确授权提交/推送。

## 11. 可直接粘贴给新会话的开场指令

> 继续shurufa的微信群红包Hook适配。请先阅读docs/plans/2026-10-09-redpacket-handoff.md和docs/plans/2026-10-09-redpacket-virtual-display.md，再读取本机项目AGENTS。目标是微信8.0.78、关闭所有系统通知、多数群免打扰、其他App前台/息屏也能静默低耗电领取，3秒是待验证目标。已同意参考WeChatKit并研究Hook；没有备用手机，用户指定应用分身微信测试。先核验分身安装/Hook作用范围是否与主微信隔离，再继续静态适配和只识别不领取探针设计。不得直接覆盖主微信、Root、清数据或调用资金接口；这些步骤须单独说明并确认。不要重做已知副屏试验、主动息屏或宣称过去已抢到；过去红包拆开结果未知。保留所有其他未提交修改，不自动提交推送。
