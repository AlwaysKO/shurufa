# 聊天采集兼容与漏采预防 实现计划

> **For Claude：** 使用 superpowers:subagent-driven-development 逐任务实现，先规范审查后质量审查；当前分支执行，不创建 worktree，不提交/推送/安装/部署。

**目标：** 微信/抖音常见控件与布局变动可通过受校验规则适配；保守兜底、有限重试和无内容诊断避免静默漏采。

**架构：** 复用 runtime_setting 保存完整规则及20版历史，移动端按来源缓存最后有效文档并异步刷新。保留同页聊天证据约束与输入/游戏/Wi-Fi守卫；元数据诊断分离页面、截图、持久化及上传，不记录姓名/正文/图片。

**技术栈：** Kotlin/Room/SharedPreferences/协程，TypeScript/Express/PostgreSQL，Vue。

## 已批准范围与事实
- 用户于2026-10-06确认“后台可下发规则 + 多重识别/保守兜底 + 漏采可见”组合方案。
- 抖音40.6.0同层右侧“语音”与页头“更多”被旧规则拒绝；微信8.0.78空页面树，首次确认前的内容事件缺兜底，系统截图间歇失败且错误码缺诊断。
- 微信发送复测15:31:26持久化、15:31:29图片和消息上传有效成功回执；不等同后台会话展示已核验。
- 不保证所有任意宿主改版无需APK，不把待传为空当成没有漏采；不扩大到非聊天页。

## 跨端契约（schemaVersion=1）
配置 `{schemaVersion:1,revision:integer,rules:Rule[]}`；状态 `{current:Config,history:Config[]}`。
Rule固定字段：`id,packageName,minVersionCode,maxVersionCode,enabled,titleIds,inputIds,bodyIds,backLabels,settingsLabels,voiceLabels,voicePosition`。
- packageName仅com.tencent.mm / com.ss.android.ugc.aweme；voicePosition为left/right/either；版本上界可null。
- id不重复、规则最多20、每个数组最多16项且无重复、字符串非空/首尾无空白/不含控制字符、资源短ID最多128字符（字母数字及_.=-）或宿主包精确:id/前缀加短ID，标签最多64个Unicode码点，不支持正则/脚本/通配全页规则。
- 文档最多32KiB；默认保留微信现行能力，抖音旧ID和40.6布局，voiceLabels含语音且位置either；结构、标题、输入区、列表、导航证据必须来自同一可见聊天层，不由配置撤销。
- GET /api/v1/mobile/chat-capture-config；后台GET/PUT /api/v1/dashboard/settings/chat-capture，POST相同路径/rollback；PUT `{expectedRevision,rules}`，rollback `{expectedRevision,revision}`。冲突409，非法400；回滚产生更高revision。
诊断POST /api/v1/mobile/chat/diagnostics，原始body固定字段：`device_id,platform,app_version_code,app_version_name,config_revision,stage,status,error_code,observed_at`。
- platform wechat/douyin；stage page/screenshot/persist/upload；各stage状态分别matched/rejected/empty_tree、ready/failed/cancelled、inserted/duplicate/failed、acknowledged/failed/waiting；error_code整数或null；observed_at毫秒时间。
- 设备必须属于当前用户；只保存每设备/平台/阶段最新状态，过期重放不能回退；区分服务器received_at与设备observed_at。复用runtime_setting，不新建聊天历史、不存任意字符串；后台GET /api/v1/dashboard/chat-capture-diagnostics?device_id=UUID。
- Android最多每平台/阶段一分钟上传一个最新诊断；上线后台暂缺端点不阻塞原采集、不无限堆诊断。主线程仅元数据调度；网络/偏好写入在输入及游戏允许的后台，有界任务。

### 任务1：后台规则、诊断与管理页
文件：新建server/src/lib/chatCaptureConfig.ts、server/src/api/chatCaptureSettings.ts、server/src/api/chatCaptureDiagnostics.ts及测试；修改server/src/app.ts；新建client/src/api/chatCapture.ts、client/src/views/ChatCaptureSettings.vue，修改client/src/api/index.ts、client/src/main.ts、client/src/App.vue。
1. 写配置合法/非法、32KiB限制、认证隔离、CAS冲突、历史回滚、诊断无内容白名单与设备归属、过期诊断回放回归；`cd server && npx vitest run src/api/chatCaptureSettings.test.ts src/api/chatCaptureClient.test.ts`应先失败。
2. 最小实现复用runtime_setting，不引入迁移或通用配置框架。
3. 管理页编辑配置/版本范围/控件和标签规则，显示版本/历史回滚和选定设备的四阶段状态，明确旧APK未上报诊断/观测过期，不把空结果称为正常。
4. 跑上述测试、server/client build；只读核对本地runtime_setting已有表。规范与代码质量审查。

### 任务2：Android规则、适配、兜底及诊断
文件：新建data/capture/adapter/ChatCapturePolicy.kt、ChatCaptureSettings.kt及测试；修改AdapterRegistry、DouyinChatAdapter、WeChatChatAdapter、PassiveChatAccessibilityService、WindowScreenshotter、MediaCropper及必要DataCollector/EventDelivery入口。
1. 先写脱敏40.6结构正例、评论/搜索/后台兄弟层反例、非法缓存/来源隔离/迟到响应、空树首次失败后的有限重试、截图错误码和诊断限频等失败测试。
2. 规则按实际App版本选择，PM元信息后台缓存；旧后台/网络失败保留内置与最后有效规则，异步刷新，不在发送/事件主线程联网。
3. 适配器同页结构证据不可配置取消。微信沿用图像聊天身份确认，首帧未确认时仅有界探测与有限重试，重试校验窗口及会话代次；抖音结构回退涵盖右侧语音，不把任意更多当聊天设置。
4. 系统截图失败保留真实错误码，瞬态错误有限重试，不循环截图、不清队列、不绕过输入优先。抖音无法安全确认的空树不扩大截图范围，呈现诊断而非伪造成功。
5. 诊断本地可见并通过有界异步请求上报后台，至少覆盖页面、截图、持久化及上传有效回执；无聊天内容、无每事件网络请求。
6. 运行定向单元测试；规范与质量审查后跑capture/service.capture/collect回归。

### 任务3：整体验证及APK
1. 检查两端默认JSON一致，真实调用接线、旧后台404回退与诊断失败不阻断业务。
2. `cd server && npx vitest run src/api/chatCaptureSettings.test.ts src/api/chatCaptureClient.test.ts && npm run build`；`cd client && npm run build`。
3. `source /home/ko/android-tools/env.sh; cd android/YuyanIme; ./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*capture*' --tests '*collect*'`（按现有测试包筛选，记录既有失败不冒充通过）。
4. 原签名非testOnly assembleOfflineDebug，核对真实版本、签名及SHA，交付/mnt/e/Projects/shurufa-android/apk；成功交付后按协议清理对应WSL APK副本。
5. 记录部署/手机升级/真机新包验收未执行。最终独立规范及质量审查，不以旧包真机记录代替新包验收。

## 任务进度（执行证据）
- Android原有抖音/生命周期/发送桥接基线：3类26项，0失败/错误/跳过，Gradle成功；日志 /tmp/shurufa-chat-compat-baseline.log。
- 后端/前端规范审查已批准；配置大小测试补为每字段合法的31,342/37,102 UTF8字节边界，诊断新鲜度纯函数动态边界验证。前端新鲜度显示依赖浏览器时钟，仅历史提示不做漏采自动裁决。
- 质量审查发现packageName/voicePosition使用String强制转换接受数组，已按RED/GREEN修复并独立复验30项。任务1规范与质量均批准；Android任务开始。
- 严格单文件测试必须用npx vitest run明确路径，npm test既有脚本含src会扩大到全套；各次全套意外运行出现既有deviceIsolation缺列/GIF超时，不宣称全套通过。

- 根代理重新执行 server/client build 均成功，client 保留既有 >500KB 分包警告。隔离 Chromium/CDP + 合成只读 mock 验证管理页两条规则、过期/近期/缺失诊断及上传回执展示，非法 `[{}]` 草稿被客户端阻止；未访问生产、未写真实数据库。私有证据 `.runtime/chat-compat-ui/`，mock和浏览器已停止。

- Android实现者 checkpoint 23类112项GREEN。独立规范审查未批准：通知补偿缺platform诊断、截图后资产准备失败静默、微信混合新旧锚点未用下发规则，已交实现者按TDD修复。根宽回归766项7失败（通知图片输入活跃取消、线程/空树诊断共6项），待定位是否测试共享状态污染，未宣称既有失败或回归通过。日志 /tmp/shurufa-chat-compat-android-regression.log。

- Android规范3项缺口修复后独立复审批准（4类23项GREEN）。质量审查发现诊断5秒超时被共享prepare覆盖300秒、配置切换/读取分锁并发返回错来源；已获得正式RED，正最小修复。
- 宽回归失败3类独立36项通过；与SDK28按键Singleton未清理测试组合重现6项顺序污染，拟仅增加测试生命周期reset，不放宽生产输入/game守卫。Notification图片取消的完整顺序是否复验通过仍待宽回归。
- Robolectric绑定Android Network慢流无法到达本地MockWebServer，非有效RED；质量审查接受分层证据：生产prepare实际Call deadline与守卫验证 + 本地Reporter慢流deadline。不得称为绑定WiFi真机E2E验收。

- 2026-10-06修正慢流测试归因：检查实际MockWebServer4.12.0字节码，QueueDispatcher.peek的throttleBody也节流POST请求体，5秒内未读完诊断JSON，requestCount为0并非已证实Android Network/Robolectric限制。先前环境推断撤回。测试改自定义Dispatcher，仅dispatch返回节流响应，默认peek正常读取POST；保留接收HTTP证据和5秒总截止断言，不改生产网络绑定。

## 最终状态
- 任务1、2独立规范/质量及跨端集成审查通过；根最终Android宽回归131类774项全过，服务端定向54项过，server/client build通过，实际两端默认一致。
- 原签名非testOnly APK已交付E盘，版本20261006.17(2026100617)、短SHA27476ecf，源/交付一致核验后只删本次WSL APK副本。完整证据与验收边界见`docs/testing/2026-10-06-chat-capture-compatibility.md`。
- 当前工作区保留未提交；未安装、部署、推送。新包真机与线上端到端验收未执行，不能把自动化验证当作用户手机已修复。
