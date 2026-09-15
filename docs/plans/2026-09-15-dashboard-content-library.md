# 后台常用语与关键词表情库 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 美化两页，提供按需收藏的预置常用语、完整关键词导航、已有表情及组内上传。

**架构：** 个人常用语继续走原同步接口；前端维护小型分类预置文案，不自动写入用户数据。表情后台合并运行 catalog、300 词规划（明确标记规划状态、不启用匹配）和当前用户自定义关键词/上传。新建独立关键词表保存空分组；公共图只读、个人图保留编辑删除；不扫描试稿目录。

**技术栈：** Vue 3、TypeScript、Express、PostgreSQL、Vitest、pg-mem。

## 已确认设计与边界
- 来源：2026-09-15 用户原始截图、关键词组内上传要求及“好”确认推荐方案。
- 常用语使用“我的常用语 / 预置常用语”页签、分类筛选、搜索、独立添加与编辑，选择预置才调用原新增接口。
- 表情使用关键词侧栏（可搜索/按有图与空组筛选）与右侧图片网格；组头上传按钮自动绑定当前词。新增关键词不强制上传。公共素材与个人上传标记来源；没有图显示可操作空态。
- 样式局限于两个页面；不改变其他页面和 Android。保留工作区原有修改，不提交无关文件，不部署远端。

## 任务 1：关键词数据与权限
文件：`server/src/api/stickers.ts`、`server/src/api/stickerLibrary.ts`、`server/migrations/015_sticker_keywords.sql`、`server/src/app.ts`、`server/src/api/stickerLibrary.test.ts`。
1. 先写集成失败测试：GET /sticker-library 合并系统/规划/个人词，按用户隔离；POST /sticker-keywords 可存空组、幂等、拒绝空值/多个词；上传后组内可见；删除最后图片仍保留词；已登录图片预览可访问而匿名不可访问。
2. `cd server && npx vitest run src/api/stickerLibrary.test.ts`，确认缺少路由导致 404 失败。
3. 添加幂等迁移、合并读取器和路由，网页预览沿用会话验证及 user_id，移动端仍要求设备头。
4. 同命令验证通过，运行已有表情与用户隔离回归。

## 任务 2：常用语与表情管理页面
文件：`client/src/views/Phrases.vue`、`client/src/views/Stickers.vue`、`client/src/views/content-library.css`、`client/src/data/phrasePresets.ts`、`client/src/api/index.ts`、`client/tests/content-library.test.ts`。
1. 用真实 Vue SFC 编译与渲染先写失败测试：预置不自动写入、点击才收藏；空关键词可见、上传绑定该词；搜索/筛选和错误状态。
2. `cd client && ../server/node_modules/.bin/vitest run tests/content-library.test.ts`，确认行为失败。
3. 实现 API 类型、分类预置短句、作用域限定样式和两页交互。上传客户端限制单张 5MB、显示成功/失败反馈、保留选择词。
4. 同命令验证通过，运行 client 全部测试及前后端 build。

## 任务 3：验收与交付
1. 检查 diff 与权限回归；实际浏览器检查两页宽屏/窄屏、GIF 图片预览、搜索、添加、失败提示。
2. 本地迁移仅应用新增表，不重跑无关迁移；是否涉及运行实例以检查结果为准，不重启无关工作。
3. 记录测试、构建、截图及无法验证项；用户审美认可与机器验证分开，不宣称发布/手机发送已经验收。

## 实现与验收记录（2026-09-15）

### 已实现
- 两页采用统一卡片、分类页签、搜索框、按钮与明确状态提示，窄屏导航仅在这两页移到顶部。
- 预置短句为 6 类共 60 条，内容位于 `client/src/data/phrasePresets.ts`；不自动入个人库。收藏直接复用 `POST /user-phrases`，现有手机同步协议未改。
- `GET /sticker-library` 只读取运行 catalog、300 词规划（只显示词和分类）、当前用户图片及独立关键词。此次本地无个人测试数据时为 374 个词、129 个有图词、304 张系统图；304 条运行素材文件均存在。
- `POST /sticker-keywords` 可单独存空词，空白/多个词/超过 100 字拒绝。图片按中英文逗号拆分归组；编辑移走末图、删除末图都保留关键词。
- 公共图只读、个人上传可改词/删除。网页公共图预览继续要求后台会话，并使用合法 user_id；移动设备头协议保持不变。
- 客户端按组上传，选择文件前锁定关键词，单张限制 5 MB，支持 GIF/PNG/JPG/WebP；成功、失败、空库和图片加载错误均有状态提示。

### 验证证据
- 后端新接口红灯：新增路由缺失返回 404，公共图网页预览原为 400；实现后定向 10 项通过。
- 独立审查发现 PATCH 移走末图丢词；新增失败测试复现，修复后新接口定向 11 项通过。
- 前端先以真实 Vue SFC 编译/渲染验证新交互缺失（5 项失败），实现后追加非法文件、上传失败、搜索与空组筛选回归。最终 client 全部 19 项通过。
- `npm --prefix client run build` 与 `npm --prefix server run build` 通过。Vite 仍提示已有单包超过 500 kB，不为本任务进行全站拆包。
- 第一次全服务端测试 470 项通过；新增移词回归后并行跑构建/浏览器的全量测试为 467 通过、4 个素材渲染测试超过默认 5 秒。无断言失败；不修改无关渲染器或测试配置，另用单 worker / 20 秒上限复跑完整套件，最终结果补在本节后。
- 真实 PostgreSQL：在隔离事务内验证 015 的中英文逗号拆词、去重、重复迁移，回滚验收数据；仅向本地应用新建表与回填的 015 迁移，未重跑旧迁移。
- 真实 Chromium：本地前端+真实后端+真实 PostgreSQL，只有设备目录使用隔离测试设备；真实收藏、分类、建词、GIF 上传、刷新持久化、删除末图保留、系统图片鉴权预览均通过。1440 px 宽屏与 390 px 窄屏检查；窄屏内容宽 362 px、无横向溢出；浏览器 pageerror 为 0。
- 浏览器验收使用随机独立 user_id，并检查清理结果。已确认测试图片、关键词和常用语记录清理完成，不修改真实用户收藏。

### 复现与调样式
- API 集成测试：`cd server && npx vitest run src/api/stickerLibrary.test.ts`。
- 前端测试：`cd client && ../server/node_modules/.bin/vitest run tests`。
- 浏览器脚本：`client/tests/content-library.browser.cjs`。启动本地前后端后运行：
  `PLAYWRIGHT_MODULE=/本机已安装/playwright CHROMIUM_EXECUTABLE=/本机/chromium node client/tests/content-library.browser.cjs`。
  默认只允许本地 5175 与 `.env.local` 中的本地 PostgreSQL；不会打印凭据，不访问线上。
- 浏览器报告与截图：`artifacts/diagnostics/2026-09-15-dashboard-content-library/`（截图按既有忽略规则仅保留本地，无强制加入 Git）。
- 样式文件：`client/src/views/content-library.css`。主色 `--lib-accent: #5261d8`；关键词侧栏 260 px；桌面图卡最小 170 px，大屏 190 px；窄屏断点 760 px。仅 `.content-library` 和包含它的当前页面布局生效。

### 交付边界
- 运行服务是本地 Vite/tsx 开发实例，代码热更新已生效；本地迁移已应用。未发布线上、未发布 APK、未改 Android、未生成/批准/搬入新素材。
- 保留全部原有未提交修改，本轮代码未自动提交或推送。
- 自动验收证明网页行为与渲染可用，不代表用户审美认可、手机最终收图/发送或生产部署已验收。

### 最终回归结果
- `cd server && npx vitest run src --maxWorkers=1 --testTimeout=20000`：47 个测试文件、471 项测试全部通过，耗时 108.83 秒。并行默认 5 秒下的 4 个渲染超时未再出现，未修改相关渲染代码或全局测试设置。
- 前端完整 19 项通过，前后端构建均退出 0；Fontconfig 有本机缓存版本警告，Vite 有既有大包提示，均如实保留。
- 浏览器最终报告 `cleanupVerified: true`；两页 390 px 下实际内容宽均为 362 px。
- 独立审查的关键词丢失问题已修复；其后测试脚本清理建议也已落实：检查删除响应、清理失败报告隔离用户、嵌套 finally 关闭资源，清理成功后才写成功报告。
