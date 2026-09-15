# 环境隔离与上报检查实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 增加本地/线上环境强隔离、一键启动、Nginx 部署模板和手机上报实时检查能力。

**架构：** Shell 启动器统一加载根目录环境文件；Android BuildConfig 按构建类型注入 API URL；管理后台始终请求同源 `/api`，开发时由 Vite、线上由 Nginx 代理。

**技术栈：** Bash、Node.js/TypeScript、Vue/Vite、Kotlin/Gradle、Nginx。

---

### 任务 1：Android 构建环境隔离

**文件：**
- 修改：`android/YuyanIme/yuyansdk/build.gradle`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/collect/ServerConfig.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/collect/ServerConfigTest.kt`

1. 编写 URL 选择失败测试，覆盖 Debug 自定义、本地默认和 Release 强制线上地址。
2. 运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ServerConfigTest'`，确认因解析函数缺失而失败。
3. 在 BuildConfig 中按 debug/release 注入 URL 和是否允许覆盖的布尔值，实现最小解析函数。
4. 重跑定向测试并确认通过。

### 任务 2：Android 上报日志

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/collect/DataCollector.kt`

1. 为设备、事件、位置三类请求增加不含隐私正文的成功与失败日志。
2. 确保所有 OkHttp Response 都通过 `use` 关闭。
3. 运行 DataCollector 相关单元测试。

### 任务 3：环境文件与一键启动

**文件：**
- 修改：`.gitignore`
- 创建：`.env.local.example`
- 创建：`.env.production.example`
- 创建：`start.sh`
- 创建：`scripts/lib/env.sh`
- 创建：`scripts/tests/start-script-test.sh`

1. 先写 Shell 测试，验证默认环境、未知环境拒绝、缺少环境文件提示和 dry-run 命令。
2. 运行测试确认失败。
3. 实现环境加载、依赖检查、迁移、ADB reverse、本地双进程管理及线上构建启动。
4. 重跑测试确认通过。

### 任务 4：管理后台代理与线上 Nginx

**文件：**
- 修改：`client/vite.config.ts`
- 创建：`deploy/nginx/shurufa.conf`

1. 让 Vite 开发代理读取 `API_BASE_URL`，默认仍为本地服务；使用默认 development mode（`local` 是 Vite 保留后缀）。
2. 添加 `myapi.dog8ball.com` Node 反代与 `mymanage.dog8ball.com` 静态站点模板。
3. 用 `npm --prefix client run build` 和 `nginx -t -c` 可行的静态检查验证语法。

### 任务 5：查看手机上报

**文件：**
- 创建：`scripts/report-status.sh`
- 创建：`scripts/watch-reporting.sh`
- 修改：`docs/android-integration.md`

1. 实现 ADB 路径发现、健康检查、reverse 状态和 Dashboard 最近记录查询。
2. 实现按 `ShurufaCollector` 标签过滤的 logcat 命令。
3. 文档写明手机未被 WSL ADB 识别时的处理步骤和正常输出判据。

### 任务 6：完整验证

1. 运行 Shell 测试。
2. 运行 `npm --prefix server test` 与构建。
3. 运行客户端构建。
4. 运行 Android 相关及全量单元测试。
5. 运行 `./scripts/report-status.sh local`，如实记录当前真机连接和上报状态。
