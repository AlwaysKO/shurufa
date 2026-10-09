# 微信视频号与抖音漏图：Mac 真机排查

## 目标及顺序

用户要求优先解决刷抖音和微信视频后后台没有截图的问题；红包的独立、默认关闭、只识别不领取探针方案已同意，暂缓实施，先完成漏图闭环。
继续遵守项目 AGENTS：当前分支、保护既有录音改动、敏感页拒绝、输入/游戏避让、有效 Wi-Fi 上传和持久预算。

1. 核验手机版本、服务、队列与线上数据，区分没有截图和没有上传。验证：本地 SQLite 只读快照、诊断枚举、线上只读 SQL 与后台实际 HTTP。
2. 获取用户目标页面样本，复现分类失败并先补失败测试。验证：手机同版本 ML Kit 与实际分类器处理同一截图，单元测试 RED/GREEN。
3. 构建、原签名验证、覆盖安装，核验数据/服务与版本。验证：相关回归、APK 元数据/签名/SHA256、安装 Success。
4. 在正常温控与有效授权条件下分别验证抖音和微信新图。验证：手机 saved → 上传 ACK → 后台列表及原图 HTTP 200/哈希；没有实际证据不宣称完成。

## 已执行的基线核验

- 设备 `AQUL024807002303`、荣耀 ELI-AN00、Android 16；实际仍为 `20261009.17 / 2026100917`。之前最后的 `.18` 包在 Windows 电脑因 USB 断开未安装，详见原页面采集计划末尾，不能把“代码已推送”当作手机已更新。
- 原采集同意 true、无障碍服务已绑定且订阅 TYPE_WINDOWS_CHANGED，微信/抖音规则均 enabled；没有通过重新授权/改开关掩盖问题。
- 本地 `page_capture_outbox.db`：pages=0、page_receipts=1；唯一回执是抖音 CONVERSATION_LIST，非视频。`video_visits.db`：active/completed 均 0。三个只读 SQLite 快照 quick_check 均 ok。
- 手机诊断：抖音浏览上传最近 acknowledged 对应上述列表图；微信 browse_capture 为 page_uncovered。
- 通过本机已有可信 SSH 入口，仅用只读 PostgreSQL会话查询这台手机：线上只有一张抖音会话列表图，没有视频/信息流访问。
- 使用服务器已有后台账号配置，经正常登录接口建立临时会话，核验实际生产后台 page-captures（分别筛选微信/抖音 media_feed）与 video-visits，三者 HTTP 200、total=0；完成后注销会话。未输出凭据、会话 Cookie 或图片正文，未修改线上配置/数据库。

## 已复现并修复的微信分类根因

只在实际前台为微信 Finder 视频号 Activity 时取一次本地诊断样本，不切换用户页面，不读取淘宝等其他前台内容。
样本只保存在 Git 忽略的私有 `.runtime/page-capture-20261009-macos/wechat-video-baseline.png`。

调用已安装输入法的 ML Kit 与 PageCapturePolicy 处理该图：

```text
关注            [270,149][389,202]
看剧            [446,149][547,202]
朋友-推荐,Q.    [597,149][1046,202]
```

原生 OCR 元素把两个导航及相邻图标合并，并没有提供可以直接匹配的独立“朋友”“推荐”。
之前只按空白拆分，即使保留原生 element 框，仍输出 kind=null / insufficient_evidence。
这证明此样本失败在分类前置条件，不能归为服务器丢图。

最小修复：导航文本除保留整段外，按实际空白、Unicode 标点和符号拆分；不猜测无分隔中文字的位置，不把 `关注推荐` 或 `推荐理由` 当作独立导航。
同帧导航区域、完整导航组合、聊天排除及密码/验证码拒绝仍生效；不新增轮询，不改变三分钟尝试预算。
已确认同一真实样本在新装 `.19` 分类器下输出 MEDIA_FEED / feed_navigation，contentKey=null；不伪造单视频身份。
这是**同一样本分类通过**，不是手机实时采集/上传已完成证明。

## 测试与交付

- 新增两个回归先实际失败：25 项中 2 项 AssertionError；日志 `navigation-red.log`。
- 修改分类后 PageCapturePolicy / PageOcrLabels / PageFrameProbe 定向组合通过；日志 `navigation-green.log`。
- 在本地构建输入快照中，将原有未提交的录音及共享入口文件恢复为 HEAD 内容，移除对应未提交新增源码/测试；工作区原文件不改。只把本轮页面修复叠加到 `83bf15d` 基线，避免把其他任务半成品安装到手机。
- 构建快照位于忽略目录 `delivery-sources/`，由 `delivery-sources.gradle` 仅覆盖本次构建的源目录；没有创建分支或 Git worktree。
- 快照相关组合：28 类 276 项测试，失败/错误/跳过均 0；包含 capture.page、诊断、聊天适配与身份、缺资源、图片上传回归。`:app:packageOfflineDebug -Pandroid.injected.testOnly=false --offline` 成功；日志 `page-delivery-tests-build.log`，XML 已归档 `delivery-test-results/`。不是全量机型或功耗验收。
- 首次验包命令误用了默认 APK 文件名，实际未找到文件；按 output-metadata.json 的真实 outputFile 修正后完成验证，不将该失败混为签名不匹配。
- 交付包：`apk/shurufa-2026-10-09-v20261009.19-2026100919-debug-d44cb0a5.apk`。
- SHA256：`d44cb0a55158dc6cc726d168d64752db28ea32b47c990586a2eb306401e4198b`。源与交付一致；API23/27/28/32/36 固定原证书、非 testOnly 全部核验通过。
- 原包名 `com.yuyan.pinyin.offline.debug` 覆盖安装 Success，复查实际版本 `.19 / 2026100919`，服务仍绑定、订阅仍完整。没有卸载/清数据，原预算和待传记录保留。

## 当前尚未完成的真机验收

- 本轮实际手机 Thermal Status 持续为 3；现有规则在 MODERATE(2) 及以上暂停浏览截图。因此即使分类修复，也不能用当时的高温状态证明新图实时保存/上传正常。没有修改系统温控、强行放行或重置截图预算。
- 用户尚未回复目标抖音页面复现请求；现场手机后来在其他 App，不擅自替用户切换或操作。已存在的历史 douyin-live.png 是聊天页，不当作推荐视频样本。
- 需手机自然降温后，用户先打开普通抖音推荐视频并保持亮屏；核验到新图/回执后再换微信视频号。两 App 共用三分钟尝试间隔，短暂打开后离开会取消延后候选，不能要求连续切换两个页面各20秒就承诺都有图。
- 尚无 `.19` 手机正式采集 saved → 生产后台原图 HTTP200 的完整新证据；抖音特定漏识别原因也尚未复现。当前不能宣称两平台已全部解决或逐条视频均有截图。
- 后台展示入口是“应用页面采集”和“视频与信息流停留”，独立于聊天采集。信息流记录继续明确“未确认单条视频”，可靠单视频分段/尾图仍属原计划未完项。

所有诊断/样本/本机构建脚本均在 `.runtime/page-capture-20261009-macos/`；正式改动只有 PageCapturePolicy.kt 与对应测试、本记录。未提交、推送或重新部署后台。

## 用户打开抖音后的复核

- 用户回复“抖音已打开”后，前后核实前台均为抖音 SplashActivity，获取一张私有诊断样本 `douyin-open.png`；未操作用户页面。
- 已安装 `.19` 的 ML Kit 与分类器对该真实推荐视频页输出 `MEDIA_FEED / feed_navigation`。同帧顶部“关注”“推荐”、底部“首页”“消息”“我”全部匹配，此页未复现额外导航分类缺陷。
- 此时系统仍报告 Thermal Status=3，IsStatusOverride=false；HAL 的 skin 温度约 41.95°C、status=3。是真实系统温控拒绝条件，不是测试覆盖值；未改变阈值或系统温控。
- 新的只读本地快照仍为 attempts=18、pages=0、page_receipts=1、video active/completed=0，三库 quick_check=ok。原有回执未增加，证明本次没有正式采集的新图。
- 同轮实际生产后台核验：两平台 media_feed 列表及 video-visits 均 HTTP200、total=0（`douyin-open-online.json`）。诊断样本分类通过不代表实时保存或上传成功。
- 下一项实机条件是自然降温至系统温控低于 MODERATE(2)，再进入抖音推荐页触发一次真实导航事件；长时间留在页面仅有播放内容变化不会触发新的周期采集。之后仍须完成保存、ACK、后台原图及微信验证。

## 用户要求放宽截图温控

用户随后明确要求“截图的温控限制放开点”，修订上述验收前提。采用最小范围调整：微信/抖音浏览截图从 MODERATE(2) 起暂停改为 CRITICAL(4) 起暂停，允许 0–3，4–6 仍拒绝；不完全删除温控，不更改系统热状态。三分钟间隔、每小时/每日预算、授权、敏感页排除、输入/游戏避让和上传网络规则保持原状。

执行计划：仅修改 BrowsingPageServiceBridge 的阈值；同步本轮构建输入快照，运行既有页面采集回归并构建；核验原签名/非 testOnly 后覆盖安装；检查实机温控、服务和新图保存/ACK/后台原图。此为单常量调整，不新增镜像实现的测试。最终安装及实时上传结果另行记录，不能以编译成功代替实机验收。

- 实际修改只有该桥接类一行，将 `THERMAL_STATUS_MODERATE` 改为 `THERMAL_STATUS_CRITICAL`；既有录音改动未纳入构建快照。
- 首次增量编译出现同一文件顶层函数重复声明，检查快照仅一份定义；以 `-Pkotlin.incremental=false` 重建通过。没有为此改动业务结构。日志 `thermal-build.log` / `thermal-build-full.log`。
- 既有 capture.page 组合：19 类、197 项，失败/错误/跳过均 0；assembleOfflineDebug 成功。API23/27/28/32/36 原证书核验通过，非 testOnly。
- 已覆盖安装 Success：`apk/shurufa-2026-10-09-v20261009.20-2026100920-debug-87892b45.apk`；SHA256 `87892b45fc463b35cf6ffb5a5eaf16f7db2172561b391feab66833a0d22a3eff`。
- 实机复查版本 `.20 / 2026100920`，无障碍服务仍绑定且订阅完整。安装时温控仍为 3，现在满足调整后的温控条件；不保证其余守卫均已满足。
- 刚安装后的生产后台复查仍是 media_feed/visits 均 0；已请用户划下一条触发真实事件，保存/上传验收继续进行，不将安装成功等同于新图成功。
- 用户回复“已划”后实机温控仍为 3，attempts 从 18 增至 19，证明本次已越过温控并预留真实截图尝试。诊断更新为抖音 browse_capture/page_uncovered；pages=0、page_receipts=1、video active/completed=0，生产后台仍无 media_feed/visits 新记录（`thermal-swiped-online.json`）。阈值调整已生效，但漏图问题尚未完全解决，需要进一步区分预检/系统截图/同帧分类/代次失效原因；当前泛化诊断及荣耀不可读 logcat 不足以直接定位，禁止猜测修复或伪造回执。

## 继续实机定位与抖音完整导航行修复

用户指出不能停在“未解决”的报告，继续执行端到端排查。荣耀 logcat 无法提供既有固定枚举日志，因此仅在忽略目录的构建输入快照内临时加入本地 prefs 诊断：固定失败枚举、错误码、导航布尔/数量，无正文或图片，不上传。正式交付须移除此临时诊断。

- 真机微信 Finder 视频号触发后输出 frame_accepted，回执从 1 增至 2，生成 active 信息流记录。实际生产后台微信 media_feed=1，原图 HTTP200、106752 bytes、SHA256 与上传值匹配；记录 `wechat-probe-accepted.json`。已取得微信真实保存/上传闭环。
- 退出后后台 video-visits=1，标记 unconfirmed_feed/incomplete/interrupted、首图引用正确；不把这次不完整访问宣称精确观看时长。
- 抖音下一次预算允许的正式请求得到 classification_rejected:insufficient_evidence；固定导航标记 top_follow=0/top_recommend=0 但 top_contains_follow=1/top_contains_recommend=1，底部首页/消息/我均1。
- 同时取得的抖音目标样本 `douyin-probe-current.png` 在手机原生 ML Kit 上复现：整行“直播团购广州美关注 商城推荐”，elements 分别为“直播团购广州美关注”“商城推荐”。连原生元素也没有独立词框，不能靠空白/标点分词恢复。样本日志 `douyin-probe-current-ocr.log`。
- 新增实际回归先失败：26 项中 1 项 AssertionError（expected MEDIA_FEED, actual null），日志 `douyin-merged-red.log`。
- 最小修复识别完整合并导航行：只接受顶部12%内、宽度至少60%、完整匹配“直播团购 + 1–6汉字位置文本 + 关注商城推荐 + 可选Q/O搜索图标”的真实行；继续要求同帧底部首页/消息/我全匹配。不切分虚构子框、不全局 contains、不硬编码城市/设备。未知导航排列仍保守拒绝，后续需真实样本再扩充。
- 回归同时覆盖缺底栏、正文位置、过窄框、仅关注推荐、推荐理由、跨平台、敏感输入拒绝。正式 Android 源码只新增该规则与对应测试；其余预存录音改动继续保留且不纳入本轮包。

### 后续实机证据修订实现

上述完整导航行文字匹配属于调查中的尝试，**最终已撤掉**，对应新增测试也撤掉，没有作为最终源码交付。原因是进一步实机出现“上播团广州关注商城 推荐”等前缀误识别，以及整帧 OCR 完全漏掉顶部但底栏正常的情况；枚举导航文字不能稳健解决识别失败。

对三个真实样本做同帧顶部裁剪实验，宽区域仍会合词；小块裁剪在前两个样本恢复了独立“关注”“推荐”的原生 OCR 框，第三个复杂背景只恢复“关注”。实验保留在 `*-tiles.log` / `*-narrow-tiles.log`，不把该实验宣称所有画面必成功。

最终选择 PageFrameProbe 有界同帧补识别：仅抖音、整帧分类为 insufficient_evidence 且底部首页/消息/我已匹配时触发；顶部12%按1/8宽、1/16步长扫描，最多15块；沿真实裁剪原点平移原生OCR框，不猜字位置、不重新截图、不借旧树或上一视频证据。每块前后复查 current/allowed，识别任务结束后回收裁剪图；一旦分类不再 insufficient_evidence 即停止。敏感输入优先拒绝，未知仍拒绝，持久截图尝试预算不变。

新增 PageFrameProbe 实际回归先失败：13项中1项 AssertionError（无补识别时不能生成 MEDIA_FEED），`nav-tiles-red.log`。测试验证只调用一次系统截图、补识别后的真实坐标可通过完整导航分类、提前停止及所有 Bitmap 回收。`nav-tiles-green.log` 页面组合与打包通过；实机上传闭环仍需后续实际结果。

### 两平台实机闭环与最终交付

- 同帧补识别安装后，抖音正式请求 frame_accepted，持久回执增至3（原列表图+微信信息流图+抖音信息流图），待传 pages=0。
- 生产后台 `douyin-nav-tiles-live.json`：微信 media_feed=1，抖音 media_feed=1。抖音新图 ID `829b67af-c036-4b1e-8c57-d6ac4fc6bf4b`，采集北京时间20:29:21，接收20:29:28；原图 HTTP200、106316 bytes、SHA256匹配。微信图同样原图200、哈希匹配。均来自正式无障碍截图/保存/上传，不是诊断样本注入。
- 两个平台各产生一条 unconfirmed_feed 访问，均 incomplete/interrupted、无可信 duration，首图引用正确。此轮完成的是漏图闭环，不宣称已实现精确单视频身份、每条视频必拍、完整首尾或精确时长。
- 最终构建已恢复官方 PageProbeDiagnostics/LocalPageFrameReader，构建输入中不存在 localSink/page_probe_local/geometry 临时诊断；临时诊断 prefs 已从手机删除，未清队列、授权或预算。
- 最终 28 类277项，失败/错误/跳过均0，日志 `final-delivery-build.log`，XML归档 `final-test-results/`。API23/27/28/32/36 原证书验证通过、非testOnly；覆盖安装 Success，实机版本20261009.20/2026100920。
- 最终包 `apk/shurufa-2026-10-09-v20261009.20-2026100920-debug-22650fd3.apk`，SHA256 `22650fd31cf76fdb5b522adf6bcda4a2a63c9048b96069a751450b4dda3f11fa`，交付元数据 `final-delivery.json`。
- 按 requesting-code-review 进行独立只读代码审查，未发现 Critical/Important 阻塞项；确认同帧坐标、敏感拒绝、守卫、OCR等待后回收与15块上限。非阻塞建议为后续补充分块期间守卫失效及最坏15块测试。
- 最终包安装后又触发一次抖音真实滑动，等待保留的三分钟预算完成最终复核；结果另行追加。工作区录音改动仍原样保留，未提交/推送或重新部署后台。
- 最终包复核已完成：`final-delivery-before-budget.json` 实际查得抖音 media_feed=2，第二张新图 `1a9dce2d-604f-4910-9135-5e419d3e0961` 在北京时间20:32:21采集、20:32:30收到；原图HTTP200、112164 bytes、SHA256匹配。此新图产生于已移除临时诊断的最终包，保留的共享间隔正常生效。微信既有新图仍200且哈希匹配。后台入口 `/page-captures`（菜单“应用页面”）。
