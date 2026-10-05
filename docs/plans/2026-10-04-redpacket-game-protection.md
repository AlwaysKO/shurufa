# 红包助手游戏保护及后台领取可行性

**目标：** 用户已选择先完成游戏保护，再研究不操作屏幕的领取。当前分支直接开发，保留全部既有改动，不提交、不切分支。

**设计：** 复用前台识别，红包交互保护与上传暂停分开发布。游戏、未确认前台、服务断线均禁止红包跳转、亮屏、截图/OCR、点击、返回和锁屏；息屏不能解除游戏保护。确认非游戏后稳定3秒再尝试内存中的最多4条通知。后续用户明确要求游戏期间保留红包、退出后依次尝试：新通知入场仍限15秒，已接收且因游戏暂存的通知改为按原始通知时间最多保留24小时，不随保护切换续期；这是本地队列期限，不保证红包仍可领取。取消正在执行的流程并废弃异步回调；未点击的通知可恢复，已开始点击的任务不自动重放。用户主动操作、关闭功能仍取消任务。

1. **测试先行：** 新增 `data/redpacket/PacketGameProtectionTest.kt`，覆盖通知不启动、队列保留/有界、旧唤醒回执无效、自动锁屏禁止、冷却恢复/过期丢弃；扩展 `data/collect/GameForegroundMonitorTest.kt` 区分息屏同步与交互保护。跑 `:yuyansdk:testOfflineDebugUnitTest --tests '*PacketGameProtectionTest' --tests '*GameForegroundMonitorTest'` 验证RED。
2. **最小实现：** 修改 `GroupRedPacketAssistant.kt`、`PacketVisualReader.kt`、`GameForegroundMonitor.kt` 的交互守卫及状态连接，阶段边界复核，保护期间不增加轮询。用上述测试及红包/前台识别/截图回归验证GREEN，审查每个实际操作入口。
3. **交付与真机：** 构建非testOnly原签名包，核验版本/哈希后放apk目录；荣耀已重新连接，先确认当前前台再覆盖安装，使用前台状态与无内容诊断验证游戏保护，不代发红包，不把无测试红包误称领取验收。
4. **研究第三条：** 完成保护后查证官方Android/微信接口、权限与隔离边界、Root/注入及虚拟显示方案，给出可行性证据和限制。只研究，不擅自Root、改微信、上传账号凭据或调用私有资金接口。

## 实施与验证

- Monitor新增独立交互保护回调，息屏仅解除上传暂停；冷启动息屏仍保护，未知前台和断线保护，确认非游戏后3秒恢复。系统/输入法覆盖层沿用上次判断。
- 红包通知、唤醒回执、读取与二次确认、点击/返回/锁屏均接入守卫，游戏期无红包tick轮询。停止流程保留最多4条未过期通知，失效原generation，已实际派发卡片点击的通知不重放；未派发的视觉确认可以重新尝试。
- 游戏期息屏保留通知；旧读取释放后按许可补一次调度，避免唯一恢复tick因reading忙而丢失。OCR已经交给系统的阶段不能承诺瞬间撤销，但后续处理和交互被阻止；已派发的手势不以额外手势强行取消。
- 首轮23项测试8项新增行为如期RED；通知年龄用例改为直接设置过期postedAt，避免把Robolectric的单调时钟推进误当作墙钟推进。审查发现的息屏/旧读取恢复两项也验证RED后修复。
- 扩展回归暴露旧PacketQueuedCancellationTest的输入单例状态污染；仅在测试夹具前后清理输入状态，未为测试改变生产输入优先策略。
- 最终644项相关测试全部通过，包含红包、前台游戏识别、collect、截图及service.capture；`:app:packageOfflineDebug -Pandroid.injected.testOnly=false`成功。日志 `/tmp/shurufa-packet-game-delivery.log`。独立审查所提3项队列边界已修复并复核。
- 交付 `apk/shurufa-2026-10-04-v20261004.18-2026100418-debug-d477c3e6.apk`，SHA256 `d477c3e691635e780190e6872a5fd7b5fb8a75bdfe089605bf54ef426ea6c4b6`；原签名API23/27/28/32/36核验、非testOnly检查通过。荣耀AQUL024807002303覆盖安装Success，手机base.apk哈希与交付一致，服务在非游戏页面显示“等待群红包”。已请求用户打开王者大厅继续验证；尚未把入场/真实红包拦截视为已验收。

### 荣耀游戏前台及真实通知验证（18:53–18:55）

- 用户打开王者后，ADB确认主用户0前台 `com.tencent.tmgp.sgame/.SGameActivity`，无障碍服务仍绑定；后台守卫paused=true，红包状态“游戏或前台确认期间暂停红包”。随后用户切换应用并回到游戏，观察到非游戏时恢复、回游戏重新保护。
- 18:54:13再次进入游戏保护，用户随后确认“已发测试红包”。主微信user0的非汇总通知包含红包标记、有contentIntent、标题符合要求、正文符合发送人加红包前缀格式；通知when为18:54:17。只输出这些结构布尔值，没有保存群名、正文或通知原始转储。
- 18:55:22复核王者仍在前台，后台和红包保护仍开启；红包识别记录停在进入游戏前的18:54:12，点击记录仍是前轮17:21:59，未见新的红包识别/点击。此次验证支持“游戏期间收到真实红包通知未抢占游戏”的保护效果，不是游戏中成功领取。
- `notice_candidate`只反映最后一条通知，系统汇总/普通通知可覆盖此前红包结果。本次系统存在汇总通知，最后值false不能直接推断原红包未识别；已以非汇总通知结构交叉核对。现有探针不暴露内存队列，因此不将队列入队状态声称为真机直接观测。队列保留/过期规则已有行为测试；本次真实红包已超过15秒，不能用于退出游戏后的新鲜通知领取验收。
- 本机无内容诊断 `/tmp/shurufa-packet-game-device-check.json`、`/tmp/shurufa-packet-game-incoming-check.json`。本次没有采集游戏帧率或延迟对照，不能据此宣称所有卡顿已解决；未发送消息/红包，未自动切换用户手机页面。

## 后台领取研究结论（2026-10-04）

### 公开接口与权限

本轮没有找到面向普通个人微信群红包的公开领取接口。微信支付文档中名称相近的[领取红包接口](https://pay.wechatpay.cn/doc/v2/merchant/4011937428)实际是商户网页调用sendBizRedPacket并校验支付签名，不适用于本项目收到的普通群红包。

普通输入法与微信处于不同UID/进程，现有无障碍权限不等于能调用微信进程内部对象或取得登录态。[Android应用沙盒](https://source.android.com/docs/security/app-sandbox)。Root/进程注入属于另一条需额外权限和版本适配的研究路线；本轮没有验证微信8.0.78内部领取机制，也没有修改微信或设备安全设置。

### 候选路线：独立虚拟显示屏

[scrcpy官方文档](https://github.com/Genymobile/scrcpy/blob/master/doc/virtual-display.md)支持创建虚拟屏并在其中启动指定App；由此推断可以先实验把微信放在副屏，物理主屏保留王者。文档证明的是显示与应用启动能力，不证明微信红包能在副屏领取。默认副屏关闭会销毁其中应用任务；不应启用将任务搬回主屏的选项，输入法也需配置为副屏本地显示。

[Android multi-resume](https://source.android.com/docs/core/display/multi_display/multi-resume)提供多显示屏Activity同时RESUMED的基础；scrcpy的[NewDisplayCapture源码](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java)对Android14+请求OWN_FOCUS。荣耀Android16满足版本条件，但MagicOS、王者和微信是否维持焦点、音频、页面归属仍待实验。

这不是普通shurufa App单凭无障碍就能实现的方案。启动第三方Activity到应用自建屏受[Android显示屏启动权限](https://source.android.com/docs/core/display/multi_display/activity-launch)约束；scrcpy使用[shell身份系统接口](https://github.com/Genymobile/scrcpy/blob/master/doc/develop.md#privileges)。标准scrcpy需要客户端与连接继续工作；脱离电脑可研究[Shizuku UserService](https://github.com/RikkaApps/Shizuku-API#userservice)，但需单独授权。其[官方手册](https://shizuku.rikka.app/guide/setup/)说明Android11+可无线调试启动，非Root重启后须重新启动，各品牌限制不同。

副屏输入应明确指向displayId，并验证主屏持续双指操作无断触。不能直接复用当前默认主屏无障碍手势，因为[dispatchGesture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,android.accessibilityservice.AccessibilityService.GestureResultCallback,android.os.Handler))存在取消当前手势的语义。即使页面隔离成立，额外渲染、OCR与微信联网仍共享手机资源，不能保证零帧率/网络影响。

### 建议的下一次实验

先通过电脑ADB建立临时副屏，以测试App验证触摸/焦点隔离，再测试微信页面和红包入口是否始终留在副屏。隔离成立后才测试用户提供的新红包；同时记录主屏任务、帧率、音频及退出副屏后的任务恢复。成功后再评估Shizuku手机独立运行及其他机型。当前未安装scrcpy/Shizuku、未创建副屏、未进行Root或微信注入，因此这条路线仍是候选实验，不能称为已实现后台领取。

## 游戏后补领修订（用户确认“对”）

- 复现：原队列按15秒丢弃游戏期间收到的通知；退出游戏后，桌面窗口事件还会被误判成用户切出而清空队列。
- 方案：请求增加游戏暂存标记，接收时仍验证15秒新鲜度，游戏及恢复冷却期间入队或进入游戏前已有未点击请求保留至原始时间24小时；最多4条，进程终止不持久化，已点击任务不重放。桌面恢复冷却的窗口事件不清队列，真实触摸/输入仍取消。
- 验证：新增长游戏后恢复、再次进入游戏仍保留、桌面过渡保留、真实触摸取消、24小时不续期测试。首轮15项中3项预期失败（长时间、再次保护、桌面事件）；实现后15项通过。独立审查再发现首条PendingIntent失效会取消整个队列；新增单入口失效仍启动后一有效通知测试先RED，针对CanceledException只结束当前请求，16项全部GREEN。

## 最终交付（2026-10-04 19时）

- Android相关666项全通过，0失败/错误/跳过；前后台相关86项全通过，两端构建通过。Android日志 /tmp/shurufa-deferred-location-final.log；独立审查两项问题均修复并复核。
- 安装包 `/Users/pj/project/shurufa/apk/shurufa-2026-10-04-v20261004.19-2026100419-debug-cf6ad97e.apk`，versionName `20261004.19`，versionCode `2026100419`，SHA256 `cf6ad97e159867e4594d213cc2a6f38cd6029ba0921ab341996a803bfbc1988e`。原证书API23/27/28/32/36核验通过，非testOnly，交付副本哈希一致。
- 尚未提交/发布后台、尚未覆盖手机；已向用户请求仅本次位置后台改动提交发布，以确保线上与电脑镜像接收端先兼容context新字段。不得把这个包称为已安装或真机已验收。
- 本次新增逻辑不恢复上一版本已丢弃的红包通知；升级重启也不保留旧内存队列。后续需新通知做长游戏等待后返回桌面测试，定位需实走对照。

### 发布与安装完成（2026-10-04 19:41–19:42）

- 线上正常发布完成，current/REVISION为194434902ee0e627042fdf638840bb81b3dbce25，服务active、/health正常；自动发布登录/后台读取健康检查成功。备份20261004T113553-194434902ee0.dump存在且非空（12160294字节）。
- 线上最终前端资源/assets/index-DtAXuGGR.js包含未知速度及诊断展示；编译后的接收校验含三个新字段。公网实际/location接口通过新增context校验，再因故意无效坐标在数据库访问前拒绝；未伪造轨迹/速度写入。
- 本机接收端已兼容，恢复荣耀USB的tcp:3000反向映射供原有电脑镜像地址使用。
- 荣耀AQUL024807002303覆盖安装Success，实际versionName=20261004.19，base.apk SHA256=cf6ad97e159867e4594d213cc2a6f38cd6029ba0921ab341996a803bfbc1988e，与交付包相同。红包/均衡定位开关仍true；无障碍Bound、通知监听器有user0活动代理、BalancedLocationService前台运行；红包状态等待群红包，非游戏paused=false。
- 真正步行对照及新红包长时间游戏后补领仍需真实场景验证，不能将安装/服务状态等同端到端验收。旧已丢弃通知和升级前内存队列不恢复。
- 仅位置后台7文件已提交推送，其余Android/文档及既有工作区改动保留。发布前86项定向测试本轮重新通过；前轮Android666项及签名验证结论不变，APK本轮未重建。
