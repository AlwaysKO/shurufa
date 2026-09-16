# APP 真实名称采集与展示实施计划

> 按已确认方案使用 superpowers:executing-plans 顺序执行；本次不委派代理，不提交或覆盖其他开发中的改动。

**目标：** APP 分布及报表 Top 应用优先显示手机系统应用名称；历史事件使用同一用户已采集的名称，已知包名映射兜底，未知完整包名保留。

**设计依据：** 用户已同意补齐手机端采集、服务端保存和后台展示，并制作新版 APK。

**架构：** 移动事件增加可选 `app_name`；Android 仅查询当前事件所属包的标签，不枚举安装列表。服务端验证名称后保存于既有 `input_event.metadata.app_name`，不改表结构、不回写历史事件；查询最近有效名称时严格按 `user_id` 与包名隔离。客户端优先名称，其次原有常见包名映射，最后完整包名；名称不改变按包名统计的身份。

**技术：** Kotlin / PackageManager / kotlinx.serialization、Express / PostgreSQL JSONB、Vue / ECharts。

## 约束
- ko 工作区的所有写入、测试、构建以 ko 执行，并检查产物归属。
- 保留 DataCollector、API、报表等文件已有改动，不部署线上、不安装或替换连接手机中的 APK。
- 名称限制 120 字符，去除空白与控制字符；非法名称忽略，不让旧客户端或可选字段阻塞事件上传。
- Android 包可见性仅声明 MAIN/LAUNCHER 查询，不申请 QUERY_ALL_PACKAGES；无法查询的包仍正常记录事件。

## 任务 1：服务端协议与名称查询
- 文件：`server/src/types/events.ts`、`server/src/api/mobile.ts`、`server/src/api/dashboard.ts`、新增 `server/src/lib/appNames.ts` 及其测试、`server/src/api/appNames.postgres.test.ts`。
- [ ] 先写失败测试：名称保存且原 metadata 不丢失；旧事件仍接收、重试不重复；同用户较新的有效名称用于历史统计，空/异常名称不覆盖；不同用户同包不串名；日报周报和 APP 分布均输出 `app_name`。
- [ ] 实现 `normalizeAppName(value: unknown): string | null`、`eventMetadata` 与 `withAppNames(pool, userId, rows)`；批量查询仅对结果中的包名查最新名称，旧数据返回 null。
- [ ] 本地 PostgreSQL 随机隔离 schema 集成验证并清理；运行既有事件回归与服务端构建。

## 任务 2：Android 采集
- 文件：新增 `AppNameResolver.kt` 与 Robolectric 测试；修改 `DataCollector.kt`、`yuyansdk/src/main/AndroidManifest.xml`。
- [ ] 测试读取中文/英文真实标签、未知/不可见包安全回退，现有 JSON 往返与持久队列保留可选 app_name，旧队列可解码。
- [ ] `MobileEvent` 增加 `@SerialName("app_name") val appName: String? = null`，recordEvent 在现有隐私同意门禁后查询当前 packageName；缓存已解析名称以避免每次按键重复查包。
- [ ] 增加 MAIN/LAUNCHER queries，绝不调用 getInstalledApplications/getInstalledPackages。
- [ ] 运行指定 Robolectric 测试与离线 debug APK 构建，检查 APK 文件与本次产物归属，不自动安装。

## 任务 3：前端显示与验证
- 文件：`client/src/api/index.ts`、`client/src/views/Applications.vue`、`client/src/views/Report.vue`；新增 `client/tests/app-names.test.ts`，扩展报表测试。
- [ ] 失败测试覆盖 `appName(pkg, reportedName)` 的优先级与未知包完整回退；真实 Applications 组件图表使用名称、保留分包统计；报表 Top 应用使用同一规则。
- [ ] APP 轴标签做截断且 tooltip 可核对完整名称和包名，按包名保留不同应用，即使名称相同也不合并。
- [ ] 运行所有前端测试、生产构建、真实浏览器检查；检查 git diff 范围、所有权，更新项目记忆并告知新版 APK 路径及历史数据回填边界。
