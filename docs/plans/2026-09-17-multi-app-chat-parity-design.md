# 多 App 发送兼容与完整聊天采集对齐

## 已确认需求与边界

来源：2026-09-17 用户本次对话。微信、QQ、抖音发送按 App 和内容类型兼容，不能随意替换已验证交付方式；GIF 和 AI 合成 GIF 保留动画。QQ、抖音采集与微信现有完整能力对齐，绝非仅通知采集。后台每个 App 一个 Tab。真机操作、发送由用户负责。

## 当前证据（不是修复完成记录）

- Windows ADB 已连接荣耀 ELI-AN00；读取到输入法 20260917.11、微信 8.0.78、QQ 9.3.60、抖音 40.5.0。WSL ADB 未列出设备，后续使用 Windows ADB。
- `2026-09-08-promote-gif-ime.md` 记录用户确认旧版 GIF 发送成功；`2026-09-09-wechat-uri-text-regression.md` 记录因 URI 留在正文而移除旧交付路径。当前 sender 走标准内容接口，旧 WechatSubmitted 类型仍在但 sender 不产生它。不能盲目恢复旧 URI 路线。
- 本地内置“谢谢”四张 GIF 实际为 12～18 帧，另留有旧静态同词素材。15 张无字合成底图实际均为 20 帧 GIF；合成渲染器已实现逐帧加字。手机实际选中素材和接收结果待用户复现。
- `AdapterRegistry` 仅注册 `WeChatChatAdapter`；现有微信适配器采集聊天视口截图，不逐条读取正文。荣耀还有空树截图及标题 OCR 分组路径。
- 后端已按 platform 存储并隔离会话，但后台概览和会话列表未按 App 筛选，不能只过滤前端当前页。

## 拟实施的最小架构

1. **发送调查优先**：对比同一素材的源文件、合成产物、Provider 字节及 MIME、宿主接口响应、实际接收效果。区分预览降级、选中旧静态图、编码丢动画及宿主转换。按证据确定各 App 路由，未验证路线不得标成功；不改素材来掩盖发送问题。
2. **采集能力对齐**：保持微信路径；为 QQ／抖音提供独立聊天页识别与标题/输入区定位，复用现有截图、裁剪、去重、队列和上传。空树场景必须先有可靠的聊天页判据，不把微信时间行点击规则直接复制到整个 QQ／抖音。核对收发变化、会话切换、键盘显示/隐藏、可见语音转文字、断网补传及单图删除等微信已有行为。
3. **后台分 App**：概览与会话查询增加受限 platform 参数，保留用户隔离；媒体统计按所选 App 的消息关联去重。Tab 切换重置会话、分页、筛选和预览，并使旧异步请求失效，避免跨 App 串图。保留既有单图删除及共享资源保护。

## 涉及文件与验证入口

- Android 发送：`expression/send/ExpressionContentSender.kt`、`ExpressionFlowController.kt`、`keyboard/InputView.kt`、`expression/render/GifTemplateRenderer.kt` 及对应测试。
- Android 采集：`data/capture/adapter/AdapterRegistry.kt`、新增 QQ／抖音适配器、`service/capture/PassiveChatAccessibilityService.kt`、`ForegroundChatCaptureBridge.kt`、必要的标题识别适配及对应测试。路径均位于 `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/`。
- 后台：`server/src/api/chatDashboard.ts`、`chatDashboard.test.ts`、`client/src/api/index.ts`、`client/src/views/ChatCapture.vue`。
- 每项先写失败回归，再最小实现；Android 定向测试与 APK 构建串行，服务端测试及前端构建单独记录。不覆盖现有未提交素材制作改动，不自动 push。

## 真机验收矩阵与待办

- 三个 App 分别测试：静态图、原始 GIF、AI 合成 GIF、Emoji 合成；用户点击发送，核对接收结果而非只看接口返回。失败不丢失现场、不能用相册成功替代发送成功。
- 三个 App 分别测试：聊天页收/发变化截图、两个会话分组、键盘有/无不截断末条内容、重复事件去重、离线落盘及恢复补传、后台 Tab 隔离及单图删除。
- QQ／抖音至少检查聊天页正例、非聊天页面反例；抖音评论和直播不得进入聊天采集。测试资料仅限用户约定页面，正式夹具脱敏，原始诊断仅本地保存。
- 当前待用户打开 QQ 测试聊天页；未取得页面证据前不虚构 viewId，也不声称 QQ／抖音已适配。

状态：需求与方案记录，尚未修改业务代码或完成真机验收。页面证据与发送根因明确后细化实现步骤。

## QQ 首次页面取证（2026-09-17）

- 用户确认已打开 QQ 后，只读取得 106 个节点，均属于 `com.tencent.mobileqq`；前台 Activity 为 `.activity.SplashActivity`，因此不能仅按 Activity 判断聊天页面。
- 聊天输入框 `com.tencent.mobileqq:id/input`、发送按钮 `id/send_btn`、顶部“聊天设置”控件 `id/19c`、标题候选 `id/3cb` 均可见；此版本标题 ID 不包含 title，不能直接套微信标题优先规则。后续须结合聊天控件组合与位置验证，并补非聊天页反例。
- 此次 XML 未包含输入法窗口，系统 `mInputShown=false`；QQ 当前输入连接记录 `contentMimeTypes=null`。须调出键盘后重新取证，不能据此永久判定 QQ 不支持图片。现行标准 sender 遇到空 MIME 声明会返回 UnsupportedTarget，这是要验证的兼容分支。
- 原始 XML 和输入法状态只留 `.runtime/multi-app-chat/`，已确认被 Git 忽略；未点击、输入、发送、安装或修改手机设置。

### QQ 键盘展开后复核

- 用户输入“谢谢”后只读复核：QQ `id/input` 内容匹配测试词，输入框移至键盘上方，`mInputShown=true`，当前 QQ EditorInfo 仍为 `contentMimeTypes=null`。截图确认输入法已展开但没有推荐图行；UIAutomator 此时仍只导出 QQ 节点，不含输入法节点，不能把 XML 不含键盘当作键盘未显示。
- 当前 `ChatEditorGate.allows` 强制要求图片 MIME 声明，故此 QQ 编辑器会被拒绝；手动合成入口也由 `expressionPanelState.chatEditor` 拦截。sender 同样在没有声明时返回 UnsupportedTarget。这是当前 QQ 无推荐/合成及标准发送能力判定的已定位原因，不等于已经证明 QQ 的所有其他路线都不可用。
- 代码还发现抖音包名不在 `ChatEditorGate.DEFAULT_CHAT_PACKAGES` 中。后续不能仅加白名单并放开 MIME：需同时验证聊天页资格和可保真交付路线，避免评论/直播误展示。
- 暂无本轮 QQ 发送记录，用户尚未选图发送；没有把截图、源码检查算成 GIF 接收验收。

## 抖音私信页面取证（2026-09-17）

- 用户确认输入后只读取得当前私信页：`id/msg_et` 是 EditText，内容为测试词“谢谢”，`id/jaw` 描述为“发送”，`mInputShown=true`，当前 EditorInfo 的 `contentMimeTypes=null`。
- 前台 Activity 为 `.splash.SplashActivity`，不能按 Activity 单独区分聊天/视频/评论。页面树同时含后台消息列表的 `id/tv_title` 与前景聊天标题 `id/vw3`、副标题 `id/lqf`；适配必须在前景聊天容器内找标题，不能全树选最靠上的 title 或最大文本。
- 当前抖音除了未在 ChatEditorGate 白名单，还未提供标准图片 MIME 声明；仅加白名单无法实现保真发送。不据空 MIME 推断所有专用交付路线均不可行。
- QQ／抖音已有正例结构和本地脱敏 XML 副本，原始记录均在 Git 忽略的 `.runtime/multi-app-chat/`。尚需非聊天页反例和接收端发送验证；未操作 UI 或发消息。

### 抖音消息列表反例

- 用户手动返回后只读取证：消息列表有 243 个节点，Activity 仍为 `.splash.SplashActivity`；`msg_et`、`jaw`、`vw3`、前景容器 `d_-`／`d-5` 均不存在，列表中的 `tv_title`、`zs3` 各有 10 个。
- 与私信正例相比，输入框、前景聊天容器及该容器内标题的组合可区分本次聊天页和消息列表。此结论仅覆盖这两个现场样例，不能推断已经排除评论页、直播页、群聊或未来 App 版本。
- 原始及脱敏反例只保存在 `.runtime/multi-app-chat/douyin-list*.xml`；尚未实现采集适配，也未把页面对照认作完整功能验收。

## 微信发送前对照取证

- 用户打开文件传输助手并输入“谢谢”；截图确认推荐首张为 `thanks-nuotuan-bow` 对应角色。当前微信输入连接声明 `[image/*, video/*, application/*]`，键盘展开，尚未点击测试图；本轮发送前无 ExpressionSendDiag 记录。
- 从应用自身缓存只读导出该 GIF 到本地诊断目录，实际解码并核对 SHA 与内置目录对应条目；随后以点击该首张的实际交付日志 SHA 为最终选中项证据，不仅依赖外观匹配。

### 微信发送复现：用户反馈静止

- 用户点击首张“谢谢”后反馈“静止”；发送后截图确认文件传输助手新增该图片。
- 15:26:31 的 `ExpressionSendDiag id=6`：目标 `com.tencent.mm`，`clipMime=image/gif`、`resolverMime=image/gif`、扩展名 gif，`committed=true`。
- 源文件与 Provider URI 读回均为 187931 字节、GIF89a 魔数，SHA 均为 `c5a931212a3207645d50dedc047bb3dedf4453f53ce3d8abe20274ffe2adf829`，与本轮手机缓存解码的 240×240、16 帧、15 个不同画面、1400ms 原 GIF 一致。
- 已排除本次选到旧静态素材及输入法交付前丢帧；标准接口接受不能证明微信按动态表情发送。尚未取得微信保存出的接收文件，因此暂不把“显示静止”写成已证明转码成 PNG，也不外推 AI 合成路径已验收。
- 下一步由用户保存本次微信图片，再只读核验该新增文件格式与帧数。不盲目恢复曾出现 URI 正文问题的旧交付方式。

### 接收端文件实证（用户保存后）

- 仅查询此次保存操作时间之后的 MediaStore 图片，新增一条 `Pictures/WeiXin/mmexport1789630129828.jpg`，系统登记 MIME 为 image/jpeg，38088 字节。
- 通过该条目 URI 只读取得文件，Pillow 实际解码为 **PNG、240×240、1 帧**，魔数 `89504e470d0a1a0a`，SHA `cc38911f959a5c92057c2284db49398544d7c4c85a7791d282ea50364f861c8b`。
- 对照发送前及 Provider 实际交付的 16 帧 GIF，当前微信标准 commitContent 路线已再次实测不能保留此素材动画；用户报告聊天显示静止，保存文件也确为静态。证据不能细分微信内部究竟在接收、发送还是保存环节编码，但足以拒绝该路线的“GIF 保真已通过”结论。
- 当前路线不是已验证的 GIF 保真方案。不能把后缀或 MediaStore MIME 当文件格式证据，更不能把 committed=true 当动态验收。替代方案仍须按 App／内容类型单独设计、回归旧 URI 落正文问题，再由用户实测。
- 文件与机器报告位于被忽略的 `.runtime/multi-app-chat/wechat-thanks-received.*`，没有扫描或读取其他相册图片。真机取证完成不等于代码修复完成；QQ／抖音完整采集与后台 Tab 也仍待实现。

## 实现批次一：后台按 App 隔离（当前执行）

1. 在 `server/src/api/chatDashboard.test.ts` 新增三平台筛选、筛选后分页/总数、共享媒体去重、非法 platform 拒绝回归；运行 `cd server && npx vitest run src/api/chatDashboard.test.ts`，先确认失败。
2. 在 `server/src/api/chatDashboard.ts` 对 overview/conversations 增加可选 platform 白名单；不传参数保持旧行为，SQL 分页前筛选，媒体按消息关联去重且保留用户隔离。
3. 在 `client/tests/chat-capture.test.ts` 增加 Vue 渲染的 Tab 切换和迟到响应回归；运行 `server/node_modules/.bin/vitest run client/tests/chat-capture.test.ts`，先确认失败。
4. 修改 `client/src/api/index.ts`、`client/src/views/ChatCapture.vue`，默认微信；切换清空选择/消息/预览/分页，旧请求不覆盖新平台；删除刷新沿用当前平台。
5. 复跑定向测试与两端 build，检查 diff；此批不改 Android、不安装 APK、不触碰其他任务文件。发送兼容和完整采集仍待后续批次。

### 批次一验证记录

- 已实现后台微信／QQ／抖音三个 Tab，默认微信；概览与会话列表由服务端按 platform 筛选，分页前筛选；平台媒体按关联消息去重，旧客户端不传 platform 保持原行为。
- 切换 Tab 清空旧选择、消息、预览、分页和统计；旧概览/会话/消息请求迟到不覆盖当前平台；删除后刷新保留当前 platform。
- TDD：新增后端 2 项先失败（混合计数、非法参数未拒绝），实现后该套件 11 项通过；新增前端 Tab 及迟到概览 2 项先失败，实现后通过，补旧消息迟到回归共 13 项通过。
- 扩大后端验证：`chatDashboard`、`deviceIsolation`、`dashboardAuth` 共 24 项通过；client 13 项组件测试通过；server/client build 均成功。前端仍有大 chunk 提示，未为此扩大重构。
- `git diff --check` 通过。独立只读审查进行中；尚未做真实浏览器视觉验收、未部署线上、未改 Android 或安装 APK，不能宣称整体需求完成。
- 原始日志留 `.runtime/multi-app-chat/`；素材和 LocationTrack 的既有未提交改动未纳入本次变更。
- 独立只读代码审查未发现重要/阻塞问题；建议补媒体边界与非微信删除刷新回归。已补未关联媒体、其他用户消息关联媒体不计入当前平台的测试，后端定向现为 **25 项通过**。其他平台独占媒体及 QQ 删除刷新专门用例尚未补，作为非阻塞覆盖缺口记录；现有实现已按平台参数刷新，不能把源码审查等同于这些用例已执行。

## 本轮范围修订：暂缓 QQ 采集

2026-09-17 用户明确“qq采集先不做，把其他的都做了先”：本轮暂停 QQ 采集适配，不删除已有 QQ 通知能力、历史记录或后台 Tab；QQ 发送兼容仍在范围内。不将本轮排期提升为永久取消 QQ 采集。

## 批次二：抖音聊天截图适配

1. 依据现场 `msg_et` / `vw3` / 前景容器 `d_-` 的关系，在 `data/capture/adapter/DouyinChatAdapterTest.kt` 先写失败测试：背景列表标题不能污染当前会话、无聊天容器/标题/输入框拒绝、截图裁到真实输入区、消息正文不结构化提取。
2. 增加 `DouyinChatAdapter.kt` 并注册，复用 CaptureCoordinator 的截图、SHA 去重、Room 持久化和上传链；不复制微信空树启发式到抖音，也不改 QQ 注册。
3. 修改 `viewportCaptureSignature`：抖音视口同微信按事件代次触发实际截图后再按图片 SHA 去重，避免无障碍树不变但图片变化被漏掉；先新增失败测试再改实现。
4. 运行新适配器、微信适配器、viewport 与采集相关回归，构建 APK。不能把结构夹具、模拟测试算真机截图/上传已验收；安装后仍由用户操作验证。

## 批次三：选图失败不丢输入（不改发送协议）

- 当前点击即清空输入，且 SavedToGallery 被当作可清面板的完成结果。先用真实 InputView 慢准备／失败／相册 fallback 回归证明提前删除，再移动清理时点。
- 点击仅准备，不删文字。标准交付返回 Sent 后，仅在同一输入连接、同一任务且完整文本快照未变时清原输入；无法安全读取快照则保留。接口接受仍不表示 GIF 保真，不用本修复冒充格式问题已解决。
- SavedToGallery／WechatSubmitted 不自动清查询面板；保留查询可重试。其他输入目标切换、用户主动关闭和编辑的原生命周期行为不改。

### 批次二/三阶段记录

- 抖音适配已注册到共用采集链，QQ 未新增适配；抖音事件在截图后做 SHA 去重，后台截图标签显示会话名与时间。脱敏现场聊天/消息列表层级进入正式 JSON 测试夹具，原始页面继续仅本地保留。
- 抖音适配器真实层级回放、适配器到 Coordinator 待上传队列与重复截图去重集成、微信适配器、前台桥、上传器等扩大回归 **37 项通过**；该阶段 APK 构建成功，未安装。
- 输入保护先以真实 InputView 准备流程验证提前删除：11 项断言失败。修改后准备/失败/相册/仅交接保留文字与卡片；仅 Sent 后同连接、完整 ExtractedText 前后快照相等才调用清理，无法读取、局部或超限保留文字。补文字变化和快照不可读回归。
- 两批独立只读审查均无确定重要/阻塞问题。采集仍需真机收发、裁剪、会话切换、评论/直播排除及离线补传；输入保护仍需真机验证。审查建议的局部/超限/第二次不可读/同文不同连接专门用例尚未全补，不声称全边界验收。
- 前端现 **14 项组件测试通过**，构建通过；Android 正进行采集、输入保护、既有 GIF 渲染与 sender 的联合回归及最终 APK 构建。
- GIF 保真发送兼容仍未实现替代路线：本轮未更改 sender 交付协议，也未恢复曾出问题的 URI 正文路线。AI 合成已有逐帧渲染；发送后的动画保持仍受宿主路线影响，不能称整体发送已修复。

### 最终阶段包与验证

- 联合定向验证 **72 项通过，0 失败/错误/跳过**：采集37、推荐解析6、GIF渲染4、sender12、InputView输入保护13。GIF渲染测试核验帧数、帧时长及逐帧加字，不代表微信接收保真。
- 最新 APK `20260917.15` 构建成功，SHA-256 `a4651cd6df507c18a042ee84758ff6855ad1f078f3fc63d2051b8c97fcbfa465`。已通过 Windows ADB `install -r` 覆盖安装，设备 base.apk SHA 与本地产物一致；保留应用数据。
- 安装前后默认输入法组件和已启用无障碍组件设置均保持原值；未代用户操作聊天或发消息。仍需用户实际打开抖音测试页验证新采集结果。
- 未完成：微信／QQ／抖音 GIF 保真发送的替代兼容路线与真机验收、AI合成发送端保真、具体素材动作自然度改进。QQ采集按本轮要求暂缓。后台与采集代码/测试通过，不宣称全部需求完成或线上已部署。

### 抖音安装后首次真机验收受阻

- 用户打开抖音测试页后只读检查，本地后台近30分钟没有新增抖音采集记录；USB reverse 仍在。
- 此时系统 `accessibility_enabled=0`、`enabled_accessibility_services=null`，dumpsys 的 Bound/Enabled services 均为空。采集服务未运行，不能把本次无记录归因于抖音适配器解析失败，也不能宣称采集通过。
- 安装记录最新时间变为 15:58:49，晚于本轮15:55:54的覆盖安装；本轮未执行第二次安装，需区分并行安装或外部变更。系统版本仍为20260917.15，版本号本身不能证明APK与本轮一致。
- 尊重本轮手机操作由用户负责的约定，请用户手动重新开启妙言输入法无障碍服务后再测试；不自动点击或擅自修改系统开关。
- 当前设备 base.apk SHA 为 `30430fcf6407117ded6111b2bb537b2605c071dfcd2dfb92036e3ac55199b268`，与本轮已验证安装的 `a4651cd6…fa465` 不同，确认之后发生了另一包覆盖；未擅自覆盖回旧包，以免抹掉其他任务交付。

### 抖音首张实际采集入库（用户重新开启无障碍后）

- 系统无障碍开启且服务 Bound，前台抖音；本地数据库新增 `douyin_screenshot` 记录，采集时间 2026-09-17 16:01:49（上海），关联1张资源。消息和会话平台均为 douyin，会话205具有非空名称。
- 实际资源解码为 WebP、1200×2245，SHA 与数据库记录一致。人工查看该入库图片：内容为聊天视口，底部当前可见消息完整，未包含编辑输入框/系统键盘；聊天区内快捷表情行保留。没有把视频卡片截图误称视频原文件采集。
- 已验证本次“打开聊天页→截图→上传入库”单次路径；尚未完成多会话隔离、收发触发、键盘显隐、断网补传与评论/直播反例的全套真机验收。后台页面视觉尚未实看，不以数据库结果替代浏览器验收。
- 当前运行的是后续另一安装包；本次实际产出表明该包含可运行的抖音截图链路，但不据此宣称其余输入保护/GIF相关代码与本轮已测包完全一致。
