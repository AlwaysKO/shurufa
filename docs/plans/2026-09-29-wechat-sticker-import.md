# 微信收藏表情导入与素材管理实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 本机助手读取微信收藏表情，线上按钮触发按 SHA256 去重导入，后台统一管理未分配图片和一图多关键词，保留线上既有内容。

**架构：** Python Windows 助手通过出站 HTTPS 领取受限任务，不开放本机端口。Express/PostgreSQL 负责配对、任务、精确去重和素材状态，Vue 在现有关键词推荐图页面增加素材库。复用 `sticker` 表，空关键词表示未分配；所有推荐入口排除此类数据。

**技术栈：** TypeScript、Express、PostgreSQL、sharp、Vue 3、Vitest/Supertest、Python 3.12、Windows 只读进程 API、PyCryptodome、Pillow。

---

## 开始前

- 阅读相邻 `2026-09-29-wechat-sticker-import-design.md`，这是本计划的范围与安全约束。
- 在 `/home/ko/project/shurufa` 当前分支开发，不创建 worktree。每阶段复查状态；此前其他 App 使用记录改动已由另外工作提交为 `b888027`，不要回滚。
- 当前最后迁移是 `035_app_usage.sql`；创建迁移前重新检查编号，不覆盖他人新增迁移。
- 不运行生产迁移、推送 main 或安装线上服务；现有 main 推送会自动部署。独立提交前检查钩子是否追加了图库文件，拒绝混入真实表情/密钥。
- 只读复用已有可行性探针逻辑，不原样复制包含密钥输出的第三方工具；若引用第三方代码，先核对并保留许可证。

## 任务 1：未分配语义与推荐隔离

**文件：**
- 修改：`server/src/api/stickers.ts`、`server/src/api/expressionSnapshot.ts`（仅必要过滤）、`server/src/stickers/bundle.ts`
- 测试：`server/src/api/stickerMaterials.test.ts`、`server/src/stickers/bundle.test.ts`、已有推荐测试

1. 先写失败测试：插入 `keywords=''` 图片，后台可读，`/api/v1/mobile/stickers` 空词和非空搜索均不可返回，推荐快照无该资产；已有带词图片保持可用。
2. 运行 `cd server && npx vitest run src/api/stickerMaterials.test.ts`，确认失败来自未分配过滤缺失。
3. 最小实现：手机直接 SQL 入口加 `btrim(keywords) <> ''`，并通过快照可见性测试确认其他链路。现有手动按词上传仍拒绝空词，不放松无关接口校验。
4. 增加并先运行清单失败测试：未分配原图导出/恢复；未分配源数据不能清空目标已有关联；空词不得成为分组。保留 SHA/路径/原图核验。
5. 修改清单逻辑通过测试；旧版清单继续可读。运行 `npx vitest run src/stickers/bundle.test.ts src/api/stickerLibrary.test.ts src/api/expressions.test.ts`。

## 任务 2：精确去重与素材库服务

**文件：**
- 创建：`server/src/stickers/materials.ts`、`server/src/api/stickerMaterials.ts`
- 创建测试：`server/src/stickers/materials.test.ts`、`server/src/api/stickerMaterials.test.ts`
- 修改：`server/src/app.ts`

1. 先写失败测试，示例契约：

```ts
const result = await matchMaterials(db, [{ sha256: existingSha }]);
expect(result[0]).toMatchObject({ status: 'existing', keywords: ['开心', '哈哈'] });
expect(await originalRowsAndOrders()).toEqual(before);
```

覆盖一条旧记录多个词、多条同 SHA 旧记录、文件改名、不同 SHA、新图、缺 SHA 旧原图和文件缺失。测试使用合成文件，不读取真实图库。
2. 运行 `cd server && npx vitest run src/stickers/materials.test.ts`，观察预期失败。
3. 实现历史 SHA 补算与匹配。素材列表按 SHA 聚合关键词、分页与分配状态；保留每个旧 ID，不删除重复行。限制查询和清单大小。
4. 新图导入在既有公共图库锁下再次查重，使用服务端计算的 SHA，不相信声明；写入 `keywords=''`。已有图只返回引用，不改变数据。失败清理仅本次新文件，不删除别的请求生成的文件。
5. 关键词接口为显式差量 `{ add: string[], remove: string[] }`；多行同图按原 ID 保留未触及词，只把新词追加到确定的一行。规范化、同组说法、已删组检查沿用现有服务；移除最后一个词保留素材。
6. 验证未登录/跨站写入被拒绝、既有排序/使用次数不变、重复导入无新增、缺文件不假成功；修改后按既有公共图库归档流程处理。

## 任务 3：助手配对与持久任务

**文件：**
- 创建：`server/migrations/036_sticker_import.sql`（执行前确认编号）
- 创建：`server/src/stickers/importJobs.ts`、`server/src/api/stickerImport.ts`
- 创建测试：`server/src/api/stickerImport.test.ts`、`server/src/api/stickerImport.postgres.test.ts`
- 修改：`server/src/app.ts`

1. 测试先行：短期配对码一次兑换；令牌撤销；同助手重复点击只有一个活动任务；任务有明确状态与租约；其他助手不能读写本任务；批次重放不重复计数。
2. 运行 `cd server && npx vitest run src/api/stickerImport.test.ts`，确认接口尚不存在导致预期失败。
3. SQL 建立最小配对、助手、导入任务、任务条目结构。SHA 索引非唯一，保留旧重复图片；任务条目以任务 ID + SHA 唯一。配对码/令牌只保存摘要，记录过期/撤销/最后在线时间。
4. Dashboard API 沿用 session + protectWrite；助手 API 挂在独立前缀 `/api/v1/sticker-import-agent`，以专用 Bearer 凭据鉴权，不绕过或削弱后台鉴权。
5. 实现固定任务类型、领取/续期/取消、摘要查询、缺图二进制上传、完成报告。客户端不能指定任意文件路径、SQL、命令或服务器下载 URL。
6. 错误配对限流；限制上传尺寸、单任务数量和有效期；取消/过期任务禁止继续写入。所有错误日志避免原样输出请求凭据或数据库下载地址。
7. 运行真实 PostgreSQL 并发/事务回归：两个请求同时上传同图只有一个新记录；进程重启后任务状态可恢复；失败条目不会使成功计数虚增。

## 任务 4：Windows 只读收藏采集

**文件：**
- 创建：`tools/wechat-sticker-import/collector.py`
- 创建：`tools/wechat-sticker-import/test_collector.py`
- 创建：`tools/wechat-sticker-import/requirements.txt`、`tools/wechat-sticker-import/THIRD_PARTY_NOTICES.md`

1. 合成测试先行：正确/错误盐与 HMAC、掩码候选、跨读取块候选、截断页、临时文件异常清理；原图、改名同图、单帧 GIF、真实多帧 GIF、截断 GIF、CDN 重定向越界。
2. 运行 `python3 -m unittest discover -s tools/wechat-sticker-import -p 'test_collector.py'`，确认缺少实现导致失败。
3. 将已验证探针整理为仅目标表情库的只读采集器：配置指定账号目录，核验进程身份，只取匹配首页 HMAC 的密钥；密钥不落盘。不能调用第三方通用聊天导出入口。
4. 获取稳定 DB/WAL 副本；校验日志代际与有效提交，应用完整提交后只读查询收藏列表。有效 WAL 不能被静默忽略；不一致重试有上限。
5. 严格 TLS + CDN 允许名单下载原图，核对微信 MD5，逐帧解码后计算 SHA256；保留原字节。异常素材跳过并报数量/原因，不自动修复转码。
6. 仅缓存原图与必要去重元数据，下载 URL/明文库/密钥不进日志或仓库；异常退出尽可能清理临时文件，下次启动清理本工具自己的过期临时文件。
7. 在本机实际微信上验证，只报告元数据；真机结果与合成测试分开记录。当前导出的 362 张只作为本机复验来源，不纳入 Git。

## 任务 5：助手传输与首次安装

**文件：**
- 创建：`tools/wechat-sticker-import/agent.py`、`tools/wechat-sticker-import/test_agent.py`
- 创建：`tools/wechat-sticker-import/install.ps1`、`tools/wechat-sticker-import/start.ps1`、`tools/wechat-sticker-import/README.md`

1. 用本地假服务先测试：配对、在线心跳、领取任务、SHA 批次比对、只上传 missing、重试幂等、取消/过期、网络断开、令牌撤销；已有 100 张时不上传其内容。
2. 运行 `python3 -m unittest discover -s tools/wechat-sticker-import -p 'test_agent.py'`，观察失败后实现最小 HTTP 客户端和状态机。
3. Windows 用户级受保护存储保存助手令牌；这是上传助手凭据，不是微信密钥，也不是管理员密码。默认只允许配置的 HTTPS 线上地址；HTTP 仅显式本地测试使用。
4. 助手仅主动出站，不监听 localhost/局域网端口。不接受远程路径/任意命令，不提升权限、不关闭系统保护、不自动重启微信。
5. 安装使用隔离 Python 环境和固定依赖，不修改全局 Python；前台/后台启动方式明确，不擅自添加开机任务。失败有可读中文提示，密钥获取失败不能显示“没有收藏”。
6. Windows 执行单元测试并做本地服务端联调，先用合成图片确认鉴权与状态，真实导出上传需要明确目标环境。

## 任务 6：后台素材库与导入进度

**文件：**
- 创建：`client/src/views/StickerMaterials.vue`、`client/src/api/stickerMaterials.ts`
- 修改：`client/src/views/Stickers.vue`
- 创建测试：`client/tests/sticker-materials.test.ts`

1. 先写 Vue 测试：素材状态筛选、分页、多关键词显示与差量编辑、匹配已有提示、助手离线、配对、导入进度、失败/取消、旧请求不能覆盖新结果。
2. 按现有 `client/tests/app-usage.test.ts` 的轻量组件测试方式加载 SFC，使用项目已有 Vitest，不引入新 UI 框架。
3. 在 `/stickers` 内增加“按关键词 / 表情素材库”切换，保留原有页面和排序行为；请求复用 `client/src/auth.ts` 的 `dashboardFetch/dashboardUpload`。
4. 配对与导入按钮展示当前助手、在线状态和一次性配对码；确认任务后轮询进度，卸载时清理轮询；不依赖选中手机。
5. 图片卡片保留原 GIF，显示所有现有关联关键词、已分配/未分配；删除关联与删除图片严格区分。异常文件在导入报告中展示原因，不作为正常素材混入。
6. 运行 `cd server && npx vitest run ../client/tests/sticker-materials.test.ts`（若默认 include 排除目录，沿用项目实际配置）；运行 `cd client && npm run build`。

## 任务 7：本地迁移与完整回归

**文件：**
- 修改：`docs/SHARED_STICKER_SYNC.md`
- 创建：`docs/testing/2026-09-29-wechat-sticker-import.md`

1. 只读取本地数据库连接的 host/port/database 标识，不打印密码；确认目标是开发数据库。
2. 执行新增迁移并核验表/索引。不要为了这一迁移盲目重跑清单导入覆盖现有数据；若用现有 `npm run migrate`，先确认当前图库基线一致。
3. 运行服务端定向与既有推荐图库回归、真实 PostgreSQL 并发测试、前后端构建、Python 测试；必要时运行服务端全量测试。
4. 本地浏览器验收：“配对→按钮→采集→已有跳过→新图未分配→关联 A/B→推荐可查→移除 A 保留 B→再次导入新增 0”。
5. 在测试记录中区分代码测试、本机微信采集、本地完整链路、线上发布四层状态；记录未验收项。此次不改 Android，不为后台功能擅自安装 APK。

## 任务 8：审查、提交与线上交付检查点

1. 使用 `superpowers:requesting-code-review` 检查鉴权、任务幂等、历史重复记录、未分配隔离、WAL 一致性和敏感数据边界；按反馈修复后重跑测试。
2. 使用 `superpowers:verification-before-completion`，核实本次命令输出，不把“文件已导出”当作“一键导入功能已上线”。
3. 检查 `git diff --check`、`git diff --cached --stat`、`git ls-files -ci --exclude-standard`，确认无真实图片、数据库、密钥、令牌、安装包及临时报告。
4. 提交范围仅本功能，中文提交说明；不得顺手提交他人改动。不能自动推送 main，因为会触发生产部署。
5. 线上访问主机身份须通过已有可信渠道核验，不关闭 SSH 校验。取得发布安排后先备份，再部署兼容代码与迁移，配置本机助手指向正式地址。
6. 线上先比对 SHA 并展示匹配统计；不覆盖现有词组/排序；新图只进未分配。线上实际完整链路完成后才能声称交付。

## 执行选择

计划已拆成独立检查点，可选择当前会话逐任务实现并审查，或另开会话使用 `executing-plans` 执行。子代理实现必须在用户选择后使用相应子代理技能；本计划本身不自动启动后台任务或生产发布。
