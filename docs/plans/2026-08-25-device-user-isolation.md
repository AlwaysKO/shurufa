# 设备用户隔离实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 以手机持久化 device_id 作为用户空间身份，让全部移动数据和后台页面按手机严格隔离。

**架构：** Express 入口中间件解析并校验移动/后台身份，路由只使用请求级 userId；Android 全部同步请求发送 X-Device-Id；Vue 维护当前设备并统一为后台 API 附加 user_id。

**技术栈：** Express、PostgreSQL、Vitest/Supertest、Kotlin/OkHttp、Vue 3/TypeScript。

---

### 任务 1：服务端请求身份
- 测试：新增双设备隔离和缺失/冲突身份测试，并先验证失败。
- 创建：`server/src/lib/requestIdentity.ts`。
- 修改：`server/src/app.ts`，为 mobile/dashboard 安装身份中间件。
- 验证：`npm test`。

### 任务 2：全部路由改用请求用户
- 修改：`server/src/api/*.ts`，删除固定 DEFAULT_USER_ID 使用。
- 修改：`server/src/analysis/analyze.ts`，逐用户分析。
- 修改：`server/migrations/009_device_user_isolation.sql`，修正常用语的用户级唯一约束。
- 测试：覆盖事件、表情、常用语、聊天、关系和后台查询隔离。
- 验证：`npm test && npm run build`。

### 任务 3：Android 请求携带设备身份
- 测试：先扩展 MockWebServer 请求断言，验证 X-Device-Id 缺失。
- 修改：采集、补全、常用语、表情和聊天上传网络代码。
- 验证：`./gradlew :yuyansdk:testOfflineDebugUnitTest`。

### 任务 4：后台用户切换
- 修改：`client/src/api/index.ts`，维护当前用户并自动附加 user_id。
- 修改：`client/src/App.vue`，增加手机选择器并切换重载当前视图。
- 验证：`npm run build`。

### 任务 5：完整验证
- 运行服务端测试与构建、客户端构建、Android 单测。
- 检查 git diff 只包含设备用户隔离相关改动。
