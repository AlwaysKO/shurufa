# 分 App 发送配置与表情面板修复计划

用户已确认：后台可配置发送策略；推荐图紧凑一行，AI/Emoji 展开键盘区域，固定三标签及返回键盘，Emoji 多行网格。GIF 不静态化、不自动相册；QQ/抖音只手动。截图显示当前输入非空却报无文字，须查会话状态而不是无条件读取历史聊天。

## 分工及顺序
1. 后端/网页独立实现配置管理、验证、持久化/历史回滚、移动端读取与测试。
2. Android 面板独立排查并先红后绿修复查询/选择状态、展开网格/返回键盘与去重复加载；只改 UI 相关文件。
3. 主线程实现 Android 配置模型/校验/缓存/异步刷新和发送器规则执行；不改素材、采集/OCR。
4. 统一回归、审查、打包。仅一个 Gradle 构建，代理需预约；不自动部署/安装/提交混合工作区。

## 前后端契约 v1
- GET `/api/v1/mobile/expression-delivery` 返回完整配置，既有 X-Device-Id。
- GET/PUT `/api/v1/dashboard/settings/expression-delivery`，既有后台认证/CSRF/user_id；GET 返回 `{current,history}`，PUT `{expectedRevision,rules}`；回滚 POST 同路径 `/rollback` `{expectedRevision,revision}` 产生新 revision，不倒退。
- 配置：`{schemaVersion:1,revision:非负整数,rules:[...]}`，最多 40 条；JSON 最多 64KiB。
- 每条：`{id,packageName,mimeTypes,minVersionCode,maxVersionCode,versionName,minSdk,enabled,method,requireCompatIme,requiredEditorExtras,action,uriKey}`。
- packageName 仅微信 `com.tencent.mm`、QQ `com.tencent.mobileqq`、抖音 `com.ss.android.ugc.aweme`；mimeTypes 为 image/gif,image/webp,image/png,image/jpeg 的非空子集。
- minVersionCode 非负安全整数，maxVersionCode null 或不小于 min；versionName null 或精确版本名（<=80字）；minSdk 23..100；enabled/requireCompatIme 布尔；method 为 commit_content 或 private_command；requiredEditorExtras 为最多8个ASCII键到32位整数的对象。
- action/uriKey：commit_content 必须为 null；private_command 为非空 ASCII 标识符（最长160/80，字母数字下划线点），minSdk>=26。requireCompatIme 仅当前内置兼容IME检查，不可远程更换组件。
- 顺序第一条匹配 package/MIME/版本/SDK 的规则生效，enabled=false 明确拒绝而非跳过；能力前置检查不通过不再尝试其他规则。管理范围内无匹配拒绝，其他 App 沿用原 MIME 协商。配置缺失/非法使用最后有效完整配置或内置默认。
- 默认：微信 GIF 仅8.0.78、minSdk26、专用命令 com.sogou.inputmethod.exp.commit，uriKey EXP_PATH_URI，requiredEditorExtras SUPPORT_SOGOU_EXPRESSION=1，requireCompatIme=true；微信静态图/QQ/抖音为标准 commit_content（minSdk23）。不新增未经验证的自动分享或相册路径。
- 规则仅可表达已内置的两种交付机制；不执行脚本、URI正文、任意文件/组件或广泛授权。所有 URI 只来自准备完成的本应用 Provider 文件，私有命令只授权当前目标包；返回true仅算已交接。
- 手机窗口显示时非阻塞刷新，5分钟节流，失败保留旧配置；原图与AI合成共用发送器。后台展示全局生效范围及版本、无规则时拒绝、交接不代表发送成功的说明。

## 验证
- 配置合法/非法、默认/更新/断网/损坏/乱序响应、精确版本/范围、禁用和能力不匹配、GIF文件/AI合成与URI字节保真。
- 后台认证、并发revision冲突、历史回滚与配置表单；网页测试及构建。
- 面板快速切换/异步回调/收起重开/换输入框/空文字、Emoji缓存/不重复绑定/网格尺寸、原有QQ抖音manual-only。

## 实施与审查进展（不代表真机或上线验收）
- 后端/网页新增配置管理与三 App Tab，runtime_setting 一行保存 current/history，SQL CAS，最多20版历史，回滚升revision。无需新迁移。测试样例中的8.0.79仅验证配置解析/路由，**不是该微信版本已实测兼容的证明**，内置仍仅启用8.0.78私有命令。
- Android新增 ExpressionDeliveryPolicy/Settings：严格完整配置验证、来源隔离SharedPreferences、单调revision、64KiB下载限制、HTTPS既有主地址且不跟随重定向；真实ImeService窗口异步刷新，5分钟节流，发送只读缓存不等网络。原件与AI合成共用规则发送器；其他App私有命令结果AppSubmitted不清文字/卡片、不冒充Sent。
- 微信8.0.78 GIF的标准commitContent已实测静态化，因此前后端校验拒绝这个组合或无精确版本的盲放；未来管理员可对实测新精确版本选择标准路径。配置不转码不等于接收端一定保真。
- 面板修复：固定三标签、主动进入AI/Emoji展开、返回键盘、同查询目录更新不抢当前tab、迟到空推荐不覆盖用户选择、Emoji仅变化时排序/刷新并复用同来源；内置fileName+SHA一致优先APK，变更SHA不复用旧文件。汉字和拼音字号不变。
- 首轮面板125项6个预期红；发送动态配置两条红；后续集成210项中206绿，4个为新边界/新布局替代旧契约；修正后完整238项绿，APK可构建，但尚不是最终交付。
- 审查追加：当前输入有选区时fallback不能漏选中字；同URL Emoji换SHA不能被新绑定复用误挡；标准发送默认SDK应保持工程原minSdk23（23/24仍可走旧MIME协商），不是本轮新设25。追加4条先红回归后再打最终包。
- 前端全量曾有一条未改动的chat-capture测试失败：mime_type undefined上调用startsWith；未擅自修其他会话的采集模块。新增配置与关联测试、前后端构建另行定向验证。
- 最新独立复验（client-all-final.log）网页全量 **154 项全部通过**，覆盖先前未通过的采集测试。此后续结果取代前面“全量有一项失败”的当前状态；本会话未修改该采集页面，不归因为本轮发送配置修复。
- 主代理重新运行配置/鉴权后端测试35项通过、前端关联20项通过，前后端构建成功（server-final.log/client-final.log）。

## 最终本地交付
- Android完整定向回归 **242 项通过、0失败/错误/跳过**，包括3App配置执行、远端原GIF/AI合成GIF字节、无静态降级、缓存/版本/禁用、面板切换/选区恢复/本地Emoji/同URL换SHA与SDK23兼容。`final-verified.log` / `final-results.json`。
- 后端35项定向、网页154项全量通过，前后端构建成功。审查追加问题均已先红后绿并复核无重要遗漏。
- APK：`artifacts/apk/shurufa-delivery-panel-20260918.15-ba2bb926.apk`；版本 `20260918.15` / `2026091815`；SHA256 `ba2bb926bff5fa778979acf52357f6c71d1c55d9258f9c1071fa1d6ac4e81a9b`；135201241字节。apksigner v1/v2通过，既有META-INF覆盖警告仍在；Git忽略已核对。
- 后台入口为“设备与数据 → 图片发送配置”，路径 `/expression-delivery`。本次没有部署线上、没有安装手机、没有代发消息、没有提交混合工作区。已异步询问是否部署，尚未收到明确选择，按只交付代码与APK执行；配置需后台上线后才能远程编辑生效，未上线时客户端保留内置规则。
- 真机连续切换的实际流畅度/布局高度以及各宿主新版本接收动画仍由用户验收，不以模拟8.0.79规则或API返回值代替实测。用户长期配置/布局要求已写项目AGENTS.md。

## 后续：普通表情分类底栏与线上发布（2026-09-18）
- 用户最新明确授权验证后部署线上，取代前述“尚未收到部署选择”的历史状态。
- 本次截图指普通表情/颜文字/符号页最底部分类工具栏，不是合成面板。原按钮高30dp、tab最大宽30dp；调整为48dp点击区域、32dp分类图标、保留横滑。尺寸集中于 `res/values/symbol_toolbar_dimens.xml`，候选/拼音字号不变。
- 单测先红：真实底栏测得30dp而期望48dp（toolbar-red.log）。新增完整容器避让检查，防止变高的底栏遮挡分页内容。
- 已执行 VS Code `code --reuse-window --goto .../symbol_toolbar_dimens.xml:5`，退出0。修改尺寸后需重新打包安装，非后台热更新项。
- 线上发布隔离目录 `.runtime/delivery-release` 基于main f7715a7，仅发送配置6新文件与4处最小接线；35后端、9前端测试及双方构建通过。清单/补丁/日志位于 `.runtime/delivery-config/release-*`，不含其他会话未提交采集等修改。
- 当前发布门禁：域名SSH缺可信主机校验记录；未绕过校验、未写线上或推送main。已向用户询问现有可信生产连接入口，公网health正常不能证明已发布本功能。
