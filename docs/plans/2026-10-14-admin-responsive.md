# 整个后台响应式适配实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 全部后台页面在手机可读可操作，保留桌面布局与全部业务能力。

**架构：** App 统一响应式外壳，各页面仅维护自身布局。沿用原路由、权限、请求及表单逻辑，宽表采用局部滚动，不引入新 UI 框架。

**技术栈：** Vue 3、TypeScript、CSS media queries、Vite、Vitest、Playwright。

---

设计依据：`docs/plans/2026-10-14-admin-responsive-design.md`。当前目录和分支直接工作，不创建 worktree。以下每项按测试失败→最小实现→测试通过推进；没有提交/推送/部署授权，不执行这些动作。

### 任务 1：建立全路由布局回归和现状证据

**文件：** 创建 `client/tests/admin-responsive.browser.cjs`，必要时创建 `client/tests/fixtures/admin-responsive.cjs`。

1. 仿照 `client/tests/page-captures.browser.cjs` 启动本地 dist 静态服务器和虚构 API 路由。测试工具允许 `PLAYWRIGHT_MODULE`、`CHROMIUM_EXECUTABLE` 环境覆盖，不写本机绝对路径；拦截所有 API，不回落真实业务服务。
2. 从 `client/src/main.ts` 核对 27 条路由清单，建立明确接口夹具（不要给所有未知接口返回同一个空对象），未知请求报错。每个页面至少等待其特有内容/加载完成，收集 pageerror。
3. 先针对总览 390px 编写外壳失败断言，再逐批增加有数据页面。核心断言示例：

```js
assert(await page.locator('.content').evaluate(el => el.clientWidth >= 360), '手机内容被侧栏挤压');
assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '页面横向溢出');
await page.getByRole('button', { name: '打开导航', exact: true }).click();
await page.getByRole('link', { name: '应用使用', exact: true }).click();
assert(await page.getByRole('button', { name: '打开导航', exact: true }).getAttribute('aria-expanded') === 'false', '切页未关闭菜单');
```

4. 运行 `npm --prefix client run build && node client/tests/admin-responsive.browser.cjs`，确认因旧窄屏布局/无导航按钮失败，而非依赖或夹具错误；输出保存在 `.runtime/admin-responsive/`。

### 任务 2：统一导航外壳

**文件：** 修改 `client/src/App.vue`、`client/src/views/content-library.css`、`client/src/views/PageCaptures.vue`、`client/src/views/VideoVisits.vue`；测试同任务 1。

1. 增加抽屉开关、路由切换关闭、Esc、遮罩关闭、焦点恢复和宽窄往返测试，测试旧版失败。
2. App 复用同一份 navGroups/菜单 DOM，实现手机顶栏及抽屉；桌面保持 224px 侧栏。使用明确的 aria 标签、展开状态、背景 inert 与焦点管理，不使用手机独立菜单数组。
3. 删除上述 3 处仅用于重写外壳的 `.layout:has(...)` 规则，保留页面内网格/筛选适配。保持已有 confirmation 与用户切换保护。
4. 跑浏览器测试，并核验 759/760/761px 与桌面来回切换、抽屉底部菜单、同路由点击、退出登录，页面不得遗留遮罩/滚动锁。

### 任务 3：统计、列表和地图页面

**文件（按失败证据最小修改）：** `client/src/views/Overview.vue`、`Timeline.vue`、`Report.vue`、`Applications.vue`、`Phrases.vue`、`WordCloud.vue`、`Completion.vue`、`AppUsage.vue`、`Activity.vue`、`Clipboard.vue`、`ClipboardHistory.vue`、`CallRecordingsView.vue`、`LocationTrack.vue`、`Relationships.vue`。

1. 扩展测试夹具，加入长文本、多列及分页数据；先记录各页手机溢出、操作不可达、图表容器宽度失败。
2. 对缺失滚动容器的表格加独立容器（保留 table 语义），只在必要处改模板；对既有容器补 `min-width:0`。媒体查询堆叠固定多列网格、换行筛选；不要全局把所有 table 改 display:block 或隐藏溢出。
3. 检查 ECharts/Leaflet 既有 resize 生命周期；只有失败才补容器尺寸更新及清理，不能刷新数据冒充 resize。
4. 重跑浏览器用例，在桌面核验表格/图表不退化。应用使用分页保留滚动、统计和局部错误。

### 任务 4：采集与配置页面

**文件：** `client/src/views/ChatCapture.vue`、`PageCaptures.vue`、`VideoVisits.vue`、`NavigationRecords.vue`、`ChatCaptureSettings.vue`、`ExpressionDelivery.vue`、`DataManage.vue`；必要时 `client/src/components/CaptureManagementControls.vue`、`CaptureRecordActions.vue`、`RetentionCleanup.vue`。

1. 测试聊天双栏、长会话名、配置表单、日期筛选、图片预览、批量操作和清理确认；先验证失败点。
2. 手机聊天列表和内容上下排列；有界列表滚动不让会话列表无限撑长。配置网格列最小值受容器限制，移除造成溢出的输入固定最小宽度。导航卡片使用 `minmax(min(280px,100%),1fr)` 等有界网格。
3. 所有危险操作保留原确认流程及选择范围，用 mock 验证，不实际清理真实数据。图片弹窗屏内且关闭入口可达。
4. 运行 `node client/tests/page-captures.browser.cjs`、`node client/tests/video-visits.browser.cjs`、`node client/tests/capture-management.browser.cjs`。只因新菜单操作路径更新旧测试，不降低业务断言。

### 任务 5：素材、词库、登录和共享弹窗

**文件：** `client/src/views/Stickers.vue`、`StickerMaterials.vue`、`SynthesisLibrary.vue`、`PersonalDictionary.vue`、`Login.vue`、`content-library.css`、`client/src/App.vue`、`client/src/components/ConfirmationDialog.vue`。

1. 新增/复用有数据浏览器用例，覆盖关键词侧栏、批量选择、上传和编辑表单、合成文字区域、词库同步、设备目录编辑、确认弹窗和登录错误。合成编辑坐标缩放保持原语义。
2. 根据失败仅调整布局；触摸目标与标签关联、文件选择入口保持可用。弹窗用动态视口上限，长内容内部滚动，按钮不被底部裁切。
3. 跑 `content-library.browser.cjs`、`sticker-groups.browser.cjs`、`synthesis-upload.browser.cjs`、`synthesis-library.browser.cjs`；移动端用真实 locator click 而非 evaluate click 跳过遮挡检查。

### 任务 6：完整验证与交付记录

**文件：** 更新本计划验收章节；不提交运行截图/真实数据。

1. 使用 `superpowers:verification-before-completion`，运行 `npm --prefix client run build`、`server/node_modules/.bin/vitest run --root client tests`，记录实际总数/失败和构建警告，不把既有失败隐瞒为通过。
2. 运行全路由响应式测试及上述业务浏览器测试；检查 360、390、768、1440 宽度、短横屏、断点边界，抽屉切页、弹窗、局部表格末列、编辑/上传/删除 mock 请求。
3. 目视检查各类页面手机/桌面截图，确认没有用 overflow:hidden 把内容裁掉。记录页面覆盖矩阵和不能验证的浏览器/真机项。
4. 按 `superpowers:requesting-code-review` 做代码审查并修复；`git diff --check` 和最终回归通过后报告变更、测试与未部署状态。是否打包 Android 遵循项目 APK 交付范围，本次网页布局不改变 APK，不以 Android 安装作为网页验收。

## 实施状态

- 设计范围已获用户确认；计划已落盘。
- 2026-10-14 用户选择当前会话继续，使用子代理驱动开发逐任务实现及审查。
- [x] 任务 1：布局测试，红失败确认并通过规范/质量审查（390px 内容实际 200px）
- [x] 任务 2：统一外壳，规范/质量双审通过；补目录隔离、退出失败可见、断点焦点红绿回归
- [x] 任务 3：统计、列表、地图，111检查及双审通过
- [x] 任务 4：采集与配置，73项检查及双审通过
- [x] 任务 5：素材、词库、共享弹窗，62项检查及双审通过
- [x] 任务 6：完整验证及最终整体审查通过
- 实施前基线：`server/node_modules/.bin/vitest run --root client tests`，28 文件、471 测试通过。
- 原计划的 `vitest run client/tests` 会额外匹配 `.runtime/delivery-release/client/tests` 历史副本，导致缺少 Android 文件的无关失败；已限定 root 为 client，不降低正式测试覆盖。
- 尚未部署。

### 执行时核对

- 旧 `content-library.browser.cjs` 读取 `.env.local` 并写本地数据库，不符合本次全 mock 验收边界；不原样运行。任务 5 使用隔离夹具补足常用语和素材回归，其他现成浏览器脚本也须先核实请求隔离。

- 外壳完成后运行旧 `page-captures.browser.cjs`：前序图片/分页/窄屏断言经过，末尾窄屏直接找隐藏的退出按钮超时；任务 4 更新测试为先打开导航再退出，不绕过实际点击或降低业务断言。

- 任务 3：15 路由、111 浏览器检查通过（含手机分页局部失败/重试、可读列宽和实际键盘横滚），规范与质量审查均通过。首次红 52 项布局/resize失败；不改接口/权限。

- 任务 4：7 路由 73 项布局/交互检查通过（empty/error为状态布局覆盖），原采集三套浏览器回归通过；保留原图打开时程序态设备切换隔离检查，并另测真实用户点击路径；规范复审及质量审查均通过。


## 最终验收记录（2026-10-14，本地）

主代理在全部生产修改冻结后统一重新执行，不仅引用子代理报告：

| 验证 | 本轮实际结果 |
| --- | --- |
| `npm --prefix client run build` | 成功；701 modules，保留既有大 chunk 告警（JS约1.61MB，gzip约547KB），不借适配任务做无关拆包 |
| `server/node_modules/.bin/vitest run --root client tests` | 28 文件、471 测试全部通过 |
| `node client/tests/admin-responsive.browser.cjs` | 统一外壳、全部菜单、759/760/761断点、焦点/目录/确认交接、退出失败重试通过 |
| `node client/tests/admin-pages.browser.cjs` | 15 路由，111 检查通过；有数据长字段、首屏手机/宽窄切换、图表resize、表格可读列宽/末列、键盘横滚、AppUsage局部分页及错误重试 |
| `node client/tests/admin-capture.browser.cjs` | 7 路由，73 检查通过；图片与短横屏、表单编辑失败重试、删除/清理取消；empty/error为状态布局覆盖 |
| `node client/tests/admin-assets.browser.cjs` | 5 路由，62 检查通过；长字段、上传/编辑/触摸排序/指定设备增量同步/登录与设备目录 |
| 既有浏览器回归 | page-captures、video-visits、capture-management、sticker-groups、synthesis-upload、synthesis-library 共6套全部通过 |
| 路由集合核对 | `main.ts` 27 条路由，与三组页面测试覆盖集合完全一致，missing=[]、extra=[] |
| `git diff --check` | 通过 |
| 审查 | 各任务规范及代码质量审查通过；最终整体审查无 Critical / Important 问题 |

### 运行方式与证据

- 新测试自带临时本地 dist 静态服务和严格虚构 API；设置 `PLAYWRIGHT_MODULE`、`CHROMIUM_EXECUTABLE` 指向本机已有测试工具即可运行。原素材三套测试通过 `DASHBOARD_TEST_ORIGIN` 指向独立本地静态 SPA 服务（API 直接返回500，不提供业务代理），由测试拦截明确接口。临时服务运行后已关闭。
- 主代理最终完整日志：`.runtime/admin-responsive-final/`，包含 build、vitest、10套浏览器日志。各批次 JSON/截图：`.runtime/admin-responsive/`、`.runtime/admin-pages/`、`.runtime/admin-capture/`、`.runtime/admin-assets/`。均为本地忽略产物，不入 Git。
- 已目视手机导航、总览、APP分布、补全、报告、数据管理、聊天、导航短横屏原图、关键词图库、登录等代表截图；补全表格曾在首轮绿中挤成单字列，已增加可读列宽断言并修复，不只以无整页溢出作为验收。
- 红测试依据：外壳390px内容仅200px；统计页首轮52项尺寸/resize失败；数据管理溢出及导航预览偏位；资产长标题/关键词/设备名称溢出或裁切。资产原始红输出只保留在会话工具记录，未单独落日志，不能把最终绿 `result.json` 当成原始红留档。

### 交付边界

- 只修改后台前端布局/交互和回归测试；不改服务器、数据库、Android、权限、采集/上传规则或统计语义。桌面保留侧栏，手机保留全部现有操作；宽表内部滚动，不藏操作列。
- 使用虚构数据与静态服务，不写真实设备/素材/词库，不运行会连接本地数据库的旧 `content-library.browser.cjs`。没有提交、推送、部署，也没有连接/安装手机。
- Chromium 多视口和触摸模拟通过，不等于真实手机浏览器、Safari或真实软键盘验收；登录缩短viewport仅模拟可视区域。线上需单独批准部署及手机人工验收。
- 本次没有 Android 改动或新 APK；网页适配交付物为前端源码/构建，不以输入法重装代替后台上线验收。
- 非阻断测试改进留待后续：Overview移除监听桩可进一步核对回调身份；部分empty/error场景可补语义文案断言；资产排序用例可补最终PATCH顺序断言。现有业务回归仍保留，未因这些建议扩大本轮生产修改。
