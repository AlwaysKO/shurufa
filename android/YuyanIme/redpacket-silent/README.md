# 手机独立静默副屏后端

Android library，供输入法宿主使用。SDK 23 可安装，服务及 Shizuku provider 仅 API 35+ 启用；UserService 只接受 shell UID 2000，业务 IPC 仅宿主实际 UID，框架 destroy 另允许自身 shell UID。

`SilentPacketService(Context)` 是 Shizuku UserService 入口。AIDL 保留原0—8号事务，并追加 `startForNotification(userId)`（9）、`observeWindow()`（10）。服务不提供任意命令、默认屏输入、截图落盘或网络接口。宿主取得 Shizuku、通知与无障碍授权后，可在其他应用或游戏中处理；仍要求主屏已知且焦点为0、主屏不是同一目标用户的微信（未知微信用户也拒绝）、副屏属于通知接收账号的微信。

`users()` 为 `{"users":[{"id":0,"name":"系统真实用户名"}]}`，仅列真实用户且精确安装 com.tencent.mm 的条目，逐个跳过 shell 无法访问的私密用户。

`start(userId)` 固定微信 LauncherUI 到私有可信副屏，不自动执行任何领取。主屏使用同一目标用户或未知用户的微信则拒绝；另一个已解析Android用户的微信可留在主屏。启动后主屏焦点/组件/用户必须保持，副屏窗口缺失时允许1500ms重试预算，其他不安全情况立即停止。任何显示创建/输入失败均不回退主屏。

`startForNotification(userId)` 用于 AUTO 与通知 PROBE，增加 PUBLIC 标志以允许无障碍读取；保留 OWN_CONTENT_ONLY、TRUSTED、OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED 和销毁内容等约束。PUBLIC 不等于主屏展示，也不具备 PRIVATE 的其他应用访问限制，因此每次操作仍严格核验显示、用户和微信窗口。两种模式之间切换会释放旧显示，清除所有旧票据；普通手机上的可访问性与静默效果仍需实机验收。

`observeWindow()` 不复制图像。在真实焦点和同一显示几何复核完成后，签发 `frameId/capturedAtUptime/displayId/userId/width/height` 操作票据，供宿主随后直接读取该副屏的微信控件。票据只消费一次，签发后1秒有效，与图片诊断共用序列，停止、过期、焦点失效、模式切换都会清空。此时刻是授权后续读取的时刻，不冒充已经采样的节点或旧图像时刻。宿主先清缓存，双次独立读取核对，再只用第二张有效票据操作。

`captureFrame()` 仅保留给手动 PRIVATE/OCR 只读诊断，返回内存 Bitmap 与帧元数据；像素复制时刻不证明底层缓存画面最近更新，不能用来替代 AUTO 的原生控件观察。显示大小来自当前物理屏的真实像素与密度，坐标必须位于该副屏，禁止主屏回退。

`launch/tap/back` 发送后重新核验焦点；短暂空窗口或同用户微信显示尚未 ON 时可在1500ms共享命令预算内等待。明确错账号、非微信窗口或不安全主屏立即停止。等待期间不执行下一动作，返回成功只表示焦点已重新确认，不表示红包到账。

`launch` 只接受微信创建、活动类型、通知用户的 PendingIntent，通过 ActivityOptions.setLaunchDisplayId 定向副屏，并显式贡献 shell 发送方 BAL 权限。Android仍可阻止启动；发送成功不证明通知群已打开，宿主使用原生控件核对通知路由和会话，禁止仅凭发送返回值点击。不要求用户填写群名，不使用群名 OCR。

同一会话最多五分钟，独立 watchdog 不受显示worker阻塞影响；正常stop/框架destroy另有三秒硬熔断。进程死亡释放显示 Binder token，私有屏设置销毁副屏内容，不调用主屏任务删除API。微信singleTask及不同OEM是否转移任务、主屏用户接管、实际通知跳转和触摸隔离均需真机验证；代码与单测通过不代表静默红包已验收。

本地检查：`:redpacket-silent:testDebugUnitTest :redpacket-silent:lintDebug :redpacket-silent:assembleDebug --offline`。
