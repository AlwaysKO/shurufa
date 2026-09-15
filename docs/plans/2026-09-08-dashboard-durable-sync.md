# 网页登录与可靠双端同步 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 后台登录和经授权的可靠离线双端上报。

**架构：** 保留 SQLite 事件队列和本地词典，追加通用请求持久队列。服务端提供幂等接收；网页会话独立于手机身份。

**技术栈：** Kotlin、SQLite、OkHttp、Android JobScheduler、Vue、Express、PostgreSQL、Vitest、Robolectric。

---

### 任务 1：网页认证
- 创建 `server/src/lib/dashboardAuth.ts` 及测试；修改 `server/src/app.ts`。
- 创建 `client/src/views/Login.vue` 和认证模块；修改 `client/src/main.ts`、`client/src/App.vue`、`client/src/api/index.ts`。
- 先写登录、401、退出、生产配置、跨站请求保护测试，运行 `cd server && npm test -- src/lib/dashboardAuth.test.ts` 确认红，再实现到绿。
- 会话 HttpOnly / SameSite，生产 Secure；本地账号限定开发，线上从环境变量配置。验证全部 Dashboard 路由无旁路，手机版继续可用。
- `cd server && npm test && npm run build`、`cd client && npm run build`。

### 任务 2：可靠上报
- 修改 `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/collect/{LocalInputStore,EventDelivery,DataCollector,ServerConfig}.kt`；新增持久请求、调度测试。
- 测试先行：位置/反馈两端确认、SQLite 重开、失败保留、200 HTML 不确认、每轮限量。运行对应 `:yuyansdk:testOfflineDebugUnitTest` 验证红绿。
- 追加 SQLite 表无损迁移；每次请求稳定 ID；网络回调与 JobScheduler 唤醒；关闭采集停止发送。
- 修改 CompletionSync / PhraseSync / StickerSync 中写请求到队列；保留现有聊天 Room 队列并核查离线/双端支持。
- 修改 `server/src/api/mobile.ts` 等必要写接口，迁移及幂等测试，避免重试重复计数；不重构无关查询。

### 任务 3：授权和过滤
- 修改 `ui/fragment/OtherSettingsFragment.kt`、`service/ImeService.kt` 及采集设置相关文件。
- 首次明确说明输入/位置、两个目的地与离线存储，确认后保持开启；拒绝/撤销不影响键盘本地学习，保留敏感输入过滤。
- 测试授权前不落盘不上传、撤销停止、重新启用、密码/无痕/认证字段过滤。

### 任务 4：集成验证与审查
- 逐任务规范和质量审查；`git diff --check`。
- 前后端全测试与构建，Android 定向和回归单测，`:app:assembleOfflineDebug`。
- 仅用 health 等无个人数据请求检查目标可达性；检查 ADB，不假称真机验证。
- 更新 `docs/android-integration.md`、配置示例和验收记录，提供 APK 路径与部署注意事项。

## 执行结果

- [x] 网页认证与真实本地代理验证，规范/质量审查通过。
- [x] 双端事件/通用报告持久队列、独立确认、失败轮转、长媒体分块读取。
- [x] 候选快照事务落盘、服务端幂等与独立反馈统计；本地迁移已应用。
- [x] 首次授权、撤销及敏感输入/聊天落库前过滤。
- [x] 前后端构建、277 项 server / 10 项 client / 82 项 Android 定向测试、APK 签名验证。
- [ ] 线上部署本轮后端及迁移（当前新接口 404，已询问部署方式）。
- [ ] 真机安装、离线/USB/恢复网络验证（ADB 无设备）。
- [ ] Android 全量套件通过（本轮尝试遇到无关表情 UI 测试 OOM，未记为通过）。

详细证据：`artifacts/dashboard-sync/verification.md`。未清理并行工作或自动提交。
