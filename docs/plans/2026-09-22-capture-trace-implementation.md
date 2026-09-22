# 采集漏截诊断实现计划

> 按 superpowers:executing-plans、test-driven-development、verification-before-completion 执行；用户已允许构建并原签名覆盖安装，保留数据。不自动提交Git。

**目标：** 为本次语音转文字漏截补足新鲜证据，不把诊断版冒充时机修复完成。
**架构：** debug包写专用ChatCaptureTrace日志，仅枚举阶段、窗口ID、页面代次、整数及布尔值；不接受任意字符串，不记录标题/正文/路径/消息哈希。复用系统有界logcat缓冲，不新增数据库或磁盘同步写。
**技术栈：** Kotlin、Android Log、JUnit/Robolectric、Gradle。

1. 新建data/capture/CaptureTrace.kt与对应CaptureTraceTest.kt：先验证关闭时无输出、开启时固定元数据格式、枚举覆盖；stub红测后实现。
2. PassiveChatAccessibilityService.kt：服务绑定、受支持App事件分类、树可读性、过期放弃、延迟调度执行、页面拒绝、空树截图生成、身份拒绝/通过、持久化结果埋点。保留已有标题带修复，不记录文本；不修改事件订阅/延迟/裁剪等策略，避免污染复现。
3. 共用MediaCropper.kt与CaptureCoordinator.kt补充系统截图请求、资源生成、入库/去重元数据，各层可观察；layer区分SERVICE身份代次、MEDIA物理请求代次与COORDINATOR（无代次）。这不是完整跨层请求关联，不把不同层相同generation当成同一请求。截图异常不输出异常message中的个人信息。
4. 定向红绿与全部capture/service.capture回归；独立只读审查；assemble原签名包并核验API23/28/36、非testOnly、源/交付哈希。
5. 操作前核对手机和版本，adb install -r指定包（禁止卸载/清数据），核对安装SHA和采集授权/服务绑定。仅用户操作聊天页与转文字，助手开启专用日志观察并记录是否新增正式缓存/入库。
6. 有根因证据后另行实施去重/触发修复。此阶段不能声称已解决输入栏冗余或转写漏截。

## 审查补充
- 补Coordinator异常固定PIPELINE_FAILED枚举，无异常文本。
- layer区分两种独立计数；增加格式/计数来源先红后绿测试，以及真实服务事件日志无正文回归。

## 实施与安装结果
- 格式测试首轮3项2失败；分层补充4项3失败，均已观察预期断言后实现。最终capture/service.capture共243项通过（0失败/错误/跳过），包括真实事件入口的日志正文隔离测试。
- 只读复审未发现新增阻断问题。诊断之外保留上一轮标题带/状态栏过滤修复，未新增正文OCR或引擎替换，未变更截图触发策略。
- assemble成功，API23/27/28/32/36固定原签名与非testOnly验证通过，源、E盘副本和手机已安装base.apk完整SHA一致。
- 用户本轮“允许”授权后执行adb install -r成功；版本20260922.12/2026092212，firstInstallTime保持2026-08-21 16:54:07，默认输入法不变，采集授权仍true，无障碍服务恢复绑定。未卸载、清数据、发消息或自动操作微信。
- 本轮唯一推荐诊断验证包：`E:\Projects\shurufa-android\apk\shurufa-2026-09-22-v20260922.12-2026092212-debug-6c7807cf.apk`。SHA256 `6c7807cf4a468029548b3f3e403bce216aa9ebada8d1009fa05792c0b6c34005`。
- 已启动仅ChatCaptureTrace标签的10分钟本地日志观察（exec session 89487，trace-live.log）；不收集其他标签或聊天文本。
- 安装后新PID14239已有新鲜证据：微信内容变化事件2048收到，TREE value=0（active/source均不可读），随后PAGE_REJECTED，当前该段没有进入系统截图。它证实空树内容事件分支拒绝采集；尚需用户重现转文字点击，核对动作事件/补拍分支后才能确定那次漏截的完整原因。
- 重复输入栏图与转写漏截尚未修复；已安装诊断不代表问题已验收通过。等待用户再次转写以获得全链路局部阶段证据。
