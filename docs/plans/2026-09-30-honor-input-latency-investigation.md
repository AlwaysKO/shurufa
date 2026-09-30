# 荣耀 200 输入卡顿调查（2026-09-30）

## 范围与证据限制

用户反馈昨天出现输入卡顿，场景不详，要求截图和上报不能影响打字。Windows ADB 只读确认设备 ELI-AN00、当前输入法 offline.debug、版本 20260929.18、更新时间 2026-09-29 20:27:53。未安装、重启、清数据或操作聊天。

读取该应用 UID 10375 的现存 logcat，并列出应用 crash_logs。取得的带日期日志只有 09-30，没有昨天的现场；不能据此排除昨天卡顿。日志仅保留本机 /tmp/shurufa-0930-lag/app.log，不入 Git。未读取聊天数据库或截图。

## 已确认日志事实

- 09-30 08:00:03：主线程 NullPointerException，ImeService.handleHardwareKeyboard:993 在输入连接为空时调用 requestCursorUpdates。
- 09-30 08:47:44：主线程 UninitializedPropertyAccessException，ImeService.onUpdateSelection:602 访问未初始化的 mInputView。对应崩溃文件生成于 08:47:45。
- 当前源码仍含两处无保护访问。它们属于输入稳定性缺陷，不证明昨天卡顿由此造成。
- 09:21:37–39 出现三次后台 NativeAlloc GC，总耗时约 183–185ms，但日志中的暂停只有几十到一百多微秒；不能把并发 GC 总耗时当作主线程卡顿时长，也不能仅凭这些行归因截图。

## 源码核对

1. ImeService.recordHostEdit 直接调用 DataCollector.recordEvent；后者同步解析应用名、网络状态，再调用 LocalInputStore.enqueue。enqueue 是 @Synchronized 数据库事务。输入记录链路存在同步落盘和系统调用，尚未测出本次耗时。
2. 【继续核对后的更正】OfflineT9Candidates 与 DataCollector 实际各自创建 LocalInputStore helper，且已启用 WAL；两者的 @Synchronized 不是同一把对象锁。候选读路径也已避免 settleLearning。此前据方法注解推断共用对象锁不成立，不据此改锁；仍需处理输入事件及选词学习的同步写事务，以及数据库层写者竞争。
3. MediaCropper 与 ImageUploadRuntime 已有 3 秒空闲门禁、多阶段检查、图片上传取消机制；不能说现有版本完全没有避让。
4. 门禁尚非全链路：PassiveChatAccessibilityService 事件树读取没有输入空闲门禁；DataCollector.flushNow 普通事件/词库同步不受图片门禁统一控制。截图请求提交后到回调中的像素复制也存在输入重新开始的窗口；编码前检查不能中断已开始的编码。
5. InputView.dispatchTouchEvent 调用 ImageUploadRuntime.noteTouch，后者在调用线程遍历并 cancel 网络 Call。应验证取消是否带来锁或 socket 关闭开销，不能未经测量宣称此处已导致卡顿。

## 建议实施顺序（未实现）

- 修复两处生命周期崩溃；与候选状态错配分开回归。
- 从输入回调移除非必要同步采集/数据库操作，保留顺序、失败语义、隐私过滤及持久化状态真实性；不以已进内存冒充已落盘。
- 隔离候选读取与后台批量数据库工作的争用，不简单删除 synchronized 破坏一致性。
- 统一输入忙闲避让至树读取、截图/像素处理/OCR、普通上报和词库同步。触摸热路径仅做轻量状态更新；在途任务在安全阶段让出，不能承诺撤销已提交的系统截图。
- 用迟到光标回调、快速输入/删除、首次打开、积压上报与截图并发测试；增加无文本内容的有限性能记录，区分按键处理、候选刷新、长帧、数据库等待。真机由用户输入，不自动发消息。

初次调查阶段仅保存需求，未修改业务代码。用户随后要求继续，第一批实现、690 项关联回归及 APK 交付见 `2026-09-30-input-priority-fixes.md`。第二批用户选择停手后落盘；事件记录侧已改为有界内存 FIFO + 空闲后台持久化，collect 与 IME 相关 267 项回归通过。选词学习仍待独立改造，真机流畅性尚未验收，不能宣称卡顿已全部解决。阶段包以实现计划的最新校验记录为准。
