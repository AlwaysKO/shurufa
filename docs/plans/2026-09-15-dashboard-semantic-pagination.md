# 后台语义分组与分页 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 按任务执行。

**目标：** 后台体现同义/近义说法共享图片组，左侧每页10组，支持首末页、前后页与跳页。

**架构：** 复用服务端已启用的16个同义词组（只导出已有常量，不改匹配逻辑），补充用户本轮明确确认的打闹说法作为后台展示/上传标签。合并关键词下图片并按来源+ID去重，不迁移/复制素材、不跨用户。未批准的242组历史草案仅用于查证，不自动变成已启用关系。客户端分页搜索结果，搜索覆盖组名与组内说法；组内上传绑定完整组标签。

**技术栈：** Vue3、TypeScript、Express、Vitest、现有浏览器验收脚本。

## 已明确边界
- 来源：用户2026-09-15要求“打你、揍你、扁你、我来打你了、过来打我啊”同一组，以及截图列表每页10个、首页尾页跳页。
- 历史核对：`server/src/expression/queryMatching.ts` 为现行16组；`assets/expression/query/semantic-groups.draft.json` 仍为待确认草案，不能套用其中全部242组或旧排除关系覆盖用户本轮明确的后台示例。
- 不修改Android/系统推荐算法，不生成新图、不改已嵌入文字。后台共组不等于所有图片文字可互换。个人上传以当前组的明确标签入库，仍通过原上传接口。
- 不新增同义词编辑器（用户本轮只要求体现），不猜测未确认词之间的关系。

## 任务1：组数据
文件：`server/src/expression/queryMatching.ts`、`server/src/api/stickerLibrary.ts`、`server/src/api/stickerLibrary.test.ts`。
1. 写失败测试：打你/揍你及确认例句同组；同张多标签图去重；开心/不开心不混；个人图不泄漏；原关键词迁移/删除仍留组。
2. 运行 `cd server && npx vitest run src/api/stickerLibrary.test.ts` 观察缺少分组数据失败。
3. 导出现有组常量，API输出aliases/confirmedAliases，按完整标签合并并去重，保留原总图计数。
4. 同命令及 `src/expression/queryMatching.test.ts` 验证，不改变匹配结果。

## 任务2：后台交互
文件：`client/src/api/index.ts`、`client/src/views/Stickers.vue`、`client/src/views/content-library.css`、`client/tests/content-library.test.ts`。
1. 先写渲染失败测试：25组每页10、首末页、跳页校验、搜索别名回首页、空结果禁用分页、翻页选中组一致。
2. 增加组内说法标签、搜索别名，上传带整组标签，新增已有别名跳到对应组而非重复建词。
3. 分页十组并删除侧栏固定滚动高度，加入首页/上一页/下一页/尾页和跳页输入；搜索筛选重置，刷新数量变化收敛页码；新增/上传成功定位正确页。
4. 前端测试及构建通过。

## 任务3：验收
1. 独立审查分组去重、上传绑定、分页边界。
2. 浏览器真实接口验证首页10组、尾页、跳页、别名搜索、组内展示、上传标签；桌面/窄屏截图。测试隔离用户并核对清理。
3. 运行前后端定向回归和构建，记录结果与线上/手机验收边界。保留原有未提交文件，不自动提交/推送。

## 完成与验收（2026-09-15）
- 本地由原374个字面词条整合为361个语义组；不删除关键词或图片。打闹组展示11种说法，包含用户列出的五项，当前共用4张运行库素材。系统仍为304张，图片按来源+ID去重。
- 列表每页10组：首页、上一页、下一页、尾页、整数跳页；搜索组名/别名与有图筛选重置到首页；零结果禁用分页；尾页数据缩减时页码收敛。
- 新增已有别名不重复建词，定位到所属组所在页。组内上传捕获完整别名标签，即使选择文件时翻页/搜索，也不会上传错组。
- 仅导出原16组常量，系统匹配函数逻辑未改。用户本轮新增的后台说法以蓝色标签标明；不启用历史242组草案，不改变Android，不改图片嵌字。

### 验证结果
- 先补失败测试，确认缺少语义组、25项未分页、已有别名被重复提交；实现后通过。
- 服务端5个定向测试文件84项通过（关键词库、查询语义矩阵、表情接口、会话鉴权、设备隔离）。服务端构建通过。
- 前端全部4个测试文件24项通过；额外覆盖尾页刷新收敛和新增成功后刷新失败。前端构建通过，仅保留既有大包提示。
- 独立只读审查未发现必须修复的问题，建议的两项边界测试已补充。
- 真实Chromium+本地API+PostgreSQL通过：10组分页、首末页/前后翻页/跳页与非法页码、五种说法同组、同图去重、上传完整标签、五种说法均能在个人图库接口搜到同一张上传图、删除保留组。
- 桌面1440px与窄屏390px截图已检查；窄屏两页内容宽362px，无横向溢出，分页按钮可见。浏览器pageerror为0，随机隔离用户测试数据清理验证通过。
- 报告及截图：`artifacts/diagnostics/2026-09-15-dashboard-semantic-pagination/`。主预览为 `stickers-semantic-pagination-desktop.png` / `stickers-semantic-pagination-mobile.png`。
- 复跑：沿用 `client/tests/content-library.browser.cjs`，设置 `DASHBOARD_TEST_OUTPUT=artifacts/diagnostics/2026-09-15-dashboard-semantic-pagination` 及本机 `PLAYWRIGHT_MODULE`、`CHROMIUM_EXECUTABLE`。脚本仅允许本地实例/数据库，随机用户隔离，清理失败明确报错。

### 调整位置及交付边界
- 每页数量：`client/src/views/Stickers.vue` 的 `PAGE_SIZE = 10`。
- 说法标签/分页样式：`client/src/views/content-library.css` 的 `.semantic-aliases`、`.keyword-pagination`；主色沿用 `#5261d8`，分页四按钮等宽。
- 后台合并规则：`server/src/api/stickerLibrary.ts` 的 `mergeSemanticGroups`，只按已声明完整说法归组，不靠子串猜测；“我打你电话”仍独立。
- 仅本地代码/构建与开发热更新已生效，未部署线上、未发布APK；保留其他任务的文件与修改。本轮未自动提交或推送。
