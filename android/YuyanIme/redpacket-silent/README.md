# 手机独立静默副屏后端

Android library，供输入法宿主使用。SDK 23 可安装，服务及 Shizuku provider 仅 API 35+ 启用；UserService 只接受 shell UID 2000，业务 IPC 仅宿主实际 UID，框架 destroy 另允许自身 shell UID。

`SilentPacketService(Context)` 是 Shizuku UserService 入口。AIDL `ISilentPacketService` 提供 start(userId)、status、capture、tap(x,y,frameId)、back、launch(PendingIntent)、stop、users。服务不提供任意命令、默认屏输入、截图落盘或网络接口。宿主必须先取得用户同意、Shizuku 授权，并持续执行输入/游戏/未知前台避让；此库自身还校验主屏已知且焦点为0、主屏不是同一目标用户的微信（未知微信用户也拒绝）、副屏仅为选定Android用户的精确微信窗口。

`users()` 为 `{"users":[{"id":0,"name":"系统真实用户名"}]}`，仅列真实用户且精确安装 com.tencent.mm 的条目，逐个跳过 shell 无法访问的私密用户。

`start(userId)` 固定微信 LauncherUI 到私有可信副屏，不自动执行任何领取。主屏使用同一目标用户或未知用户的微信则拒绝；另一个已解析Android用户的微信可留在主屏。启动后主屏焦点/组件/用户必须保持，副屏窗口缺失时允许1500ms重试预算，其他不安全情况立即停止。任何显示创建/输入失败均不回退主屏。

`capture()` 只返回当前副屏的内存 Bitmap。随后 `status().frameId` 对应该截图的单调编号；`tap` 只接受最近未消费且复制后1秒内的编号，范围为480×800。服务在截图前后、点击前后重新校验焦点。每次 launch/tap/back 前要求下一次真实渲染，避免操作后继续读取操作前缓存；没有新操作的静态画面可再次复制。`back` 仍只在严格副屏门禁下执行。

`launch` 只接受微信创建、活动类型、选定用户的 PendingIntent，通过 ActivityOptions.setLaunchDisplayId 定向副屏，并显式贡献 shell 发送方 BAL 权限。Android仍可阻止启动；发送成功不证明通知群已打开，宿主必须核对画面群名，禁止仅凭发送返回值点击。

同一会话最多五分钟，独立 watchdog 不受显示worker阻塞影响；正常stop/框架destroy另有三秒硬熔断。进程死亡释放显示 Binder token，私有屏设置销毁副屏内容，不调用主屏任务删除API。微信singleTask及不同OEM是否转移任务、主屏用户接管、实际通知跳转和触摸隔离均需真机验证；代码与单测通过不代表静默红包已验收。

本地检查：`:redpacket-silent:testDebugUnitTest :redpacket-silent:lintDebug :redpacket-silent:assembleDebug --offline`。
