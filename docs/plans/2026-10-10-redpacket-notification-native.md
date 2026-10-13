# 通知驱动的静默红包控件流程实施计划

> 按 Superpowers 的子代理开发、测试优先和独立审查流程执行。用户已授权直接开发；保持当前分支，不提交、不操作已带走的手机。

**目标：** 自动接收任意群的真实微信红包通知，按通知所属账号和 PendingIntent 进入隔离副屏，使用无障碍控件完成领取，不要求输入群名，不以群名 OCR 为领取前提。

**架构：** AUTO 使用可被无障碍读取的 PUBLIC 副屏，保留 OWN_CONTENT_ONLY、TRUSTED、OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED 等隔离约束。通知 UID、PendingIntent 创建者、后端焦点账号和控件窗口共同核验来源；内部会话核对使用通知标题和控件原文，无模糊 OCR 纠错。旧 PRIVATE/OCR 入口只保留手动只读诊断，不作 AUTO 回退。

**技术：** Kotlin、Android AccessibilityService、Java Shizuku UserService、AIDL、JUnit/Robolectric。

## 共同接口

- AIDL 追加 `String startForNotification(int userId)=9`、`Bundle observeWindow()=10`，不改变已有事务编号。
- `observeWindow` 不复制图片，真实验证当前副屏后返回 `frameId/capturedAtUptime/displayId/userId/width/height`；frameId 与原 FrameGate 共用单次消费、1秒有效期。失效、停止、跨模式切换均清空票据。
- 最终接口另含固定 `packetUi=NONE/RECEIVE/DETAIL`，来自本次最终焦点的精确微信 Activity 分类。已知接收页只允许拆开，已知详情页结合原生结果布局才能确认到账；未知页面不假确认。
- 新 SDK 模型 `NativePacketTicket(frameId:Long,capturedAt:Long,displayId:Int,userId:Int,width:Int,height:Int)`；`NativePacketSnapshot(ticket,windowId:Int,page:PacketPage,targets:Map<String,IntRect>,signatures:Map<String,String>)`。
- `NativePacketReader.read(ticket): NativePacketSnapshot?` 在后台读取精确 displayId 的单一微信应用窗口。原始控件文本仅短时内存，禁止写日志/文件/上传；窗口不明、额外应用覆盖、跨账号、缺控件均失败关闭。
- 新 runner 后端 `NativePacketBackend.observe():NativePacketSnapshot? / tap(x,y,frameId) / back()`。不伪造 Bitmap、时间或主屏来源；两次独立控件读取一致后才操作，点卡片前先持久预留防重放，卡片/拆开最多各一次，结果需独立确认。
- 首读返回时仍须在1秒内，只作最多4秒的比较基线；实际动作只消费新鲜的第二张1秒票据。精确目标显示可存在薄的 SYSTEM 边缘栏，其遮挡文字/控件排除，前后完整窗口集合变化拒绝；其他覆盖窗口或额外应用窗口拒绝。

## 任务 1：后端可访问副屏及操作票据

文件：`redpacket-silent/.../ISilentPacketService.aidl`、`ShellDisplay.java`、`SilentPacketService.java`，新增有需要的纯 Java 策略及测试。

先测试 PUBLIC/PRIVATE 模式互斥、票据有效期/单次消费/坐标范围、模式切换与停止失效、启动焦点过渡等待。再实现新入口，旧 `start` 保持 PRIVATE。PUBLIC 不移除主屏焦点与用户核验，不允许 display0 或任意包/命令。

## 任务 2：精确副屏的原生控件读取

文件：新增 `NativePacketReader.kt`、`NativePacketPageParser.kt` 和相应测试；在既有辅助服务生命周期注册/清理只读 service 引用。

先以合成控件树覆盖完整群标题、附带人数、图标与文字分离、已领取/过期/专属/自己发出的红包、唯一拆开按钮、真正成功/领完结果、覆盖窗口、空树、错 display、主屏和歧义。读取有节点/深度/文本长度上限，关闭无障碍缓存只在任务读取时；控件路径与签名用于两次目标确认，不依赖微信混淆资源 ID。

## 任务 3：通知任务领取流程和运行时接线

文件：新增 `NativePacketRunner.kt` 及测试，修改 `SilentPacketRuntime.kt`；引擎固定枚举和诊断可按需要追加。

先覆盖真实会话路由失败、双读一致才点击、只读模式零点击、预留失败零点击、重复任务、陈旧票据、窗口/账号改变、卡片与拆开各一次、结果重复确认、取消及超时。AUTO/通知 PROBE 改走新入口与 reader；手动旧诊断仍只读。UserService 版本升5，结果文案区分控件不可读与未确认到账。

## 本地验证与交付

由主代理统一执行 Gradle，避免并发争用构建目录：

```bash
source .runtime/macos/android-env.sh
./android/YuyanIme/gradlew -p android/YuyanIme :redpacket-silent:testDebugUnitTest :yuyansdk:testOfflineDebugUnitTest --tests 'com.yuyan.imemodule.data.redpacket.*' --tests 'com.yuyan.imemodule.data.collect.Game*Test' --tests 'com.yuyan.imemodule.data.collect.ImageUploadScheduleTest' --tests 'com.yuyan.imemodule.data.collect.InputPriorityWiringTest' :app:assembleOfflineDebug --offline --console=plain
```

先分别记录 RED，再合并 GREEN，交叉审查后执行相关全部测试与 APK 原签名校验。更新测试记录，明确 PUBLIC 副屏可访问性、微信控件适配和真实到账仍待后续真机验收；不得以合成测试冒充已领取。手机已被带走，本轮不再发起真机操作或要求新红包。

## 实施进度

三项代码任务已实现并通过交叉审查，最后产物与真实检查数量见测试记录。收到通知到领取结果的本地状态机、路由、取消、窗口和票据边界均有回归；目前没有本版真实到账证据，保持待用户后续统一验收。
