# 2026-09-08 网页登录与可靠上报验收

## 已验证

- Server：27 个文件、277 项测试通过。命令 `npx vitest run src --maxWorkers=2 --testTimeout=30000`；后端 TypeScript 构建通过。
- Client：10 项认证测试通过，Vue/TypeScript/Vite 构建通过。保留原有大 bundle 警告。
- Android：最终定向 23 个测试类、82 项测试，0 失败、0 跳过；覆盖 collect、completion、CaptureCoordinator、capture.net、ImeServiceKeyEvent。
- APK：`:app:assembleOfflineDebug --max-workers=1` 通过，apksigner v1/v2 签名校验通过。包名 `com.yuyan.pinyin.offline.debug`，versionCode `2026090815`，minSdk 23、targetSdk 36、arm64-v8a。
- 本地真实 `http://localhost:5175` 代理：未登录 401 → 本地账号登录 200 → session 200 → logout 204 → 同一 cookie 再访问 401。未读取任何真实个人记录。
- 本地数据库已执行新增迁移 `013_durable_reports.sql`，未清理或改写历史输入。
- 在独立临时 PostgreSQL schema 中验证：20 个同 ID 并发报告只产生一次计数；人为约束使效果写入失败时回执一起回滚；解除约束重试可成功。测试 schema 已删除。
- 规范和质量审查均完成，已修正：认证旧请求覆盖新会话、退出后用户目录回填、反代 Host、不存在云候选时反馈丢失、关闭后剪贴板落盘、聊天敏感内容过滤、失败队头阻塞、CursorWindow 大媒体错误。
- `git diff --check` 通过。

## 未完成/环境限制

- ADB 当前无设备；未安装到手机、未做真机断网/联网、USB 双端及重启验收。
- 线上 `https://my.dog8ball.com/health` 返回 200，但用空非法报告和合成设备 ID 探测 `/api/v1/mobile/reports` 返回 404。线上尚需部署本轮后端、迁移 013 和配置独立后台凭据；已询问部署方式。未擅自修改线上配置或数据库。
- 新通用报告未部署前会保留在 APK 本地队列，不降级到不支持幂等的旧计数接口。原输入事件沿用已有 batch 接口。
- Android 全量测试曾在无关的 `ExpressionManualSearchInputViewTest` 资源加载处触发 `OutOfMemoryError`；终止耗尽内存的该轮测试进程（退出 143），不能视为全量通过。随后单独 APK 构建及上述 82 项定向测试成功。
- 默认 server 全量命令与 Android 编译并行时，一项 GIF 渲染测试超过其 5 秒超时；限制 worker 并放宽执行时间后全量 277 项通过，未修改该无关测试的断言。
- 未进行浏览器端到端 UI 操作；完成的是前端测试/构建和真实本地 HTTP 认证链路验收。
- 手机接口沿用设备 UUID 身份协议，网页登录不等于完整公网设备认证加固。

## APK

`artifacts/dashboard-sync/shurufa-debug.apk`（约 81 MiB）

SHA-256：`5de49f63cfc188343e279b0dcdfc6bf3afa7da7bb169a46d945a3281b1fbef48`

首次打开应用设置，明确同意个人数据同步后启用；本地 USB 用 `adb reverse tcp:3000 tcp:3000`。未执行 Git 提交，所有本轮修改保留当前分支；未动并行进行的表情素材制作。
