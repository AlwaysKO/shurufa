# 后台管理个人词库与换机同步实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 后台绑定新旧手机，分别查看真实上报的个人词条及学习记录，管理共享个人词库并恢复到手机。

**架构：** 手机原始记录、后台词语决策、手机远端恢复层相互独立；恢复层绝不再次作为本机学习上报。默认设备隔离，后台显式绑定才共享词库，不改变聊天等其他数据隔离。手机使用单独持久同步凭据；后台会话及 CSRF 校验复用既有实现。

**技术栈：** PostgreSQL / Express / Vue / Android SQLite / Kotlin / OkHttp。

## 已确认范围与边界

- 来源：2026-09-16 当前对话，用户选择后台绑定方案，要求新旧手机上报明细均可见；重启 WSL 后源码恢复可读。
- 只同步已合法获得的个人词条及学习信息。沿用采集同意开关、敏感输入过滤；系统公开词典读取仍由已有首次启动迁移处理。不读取其他输入法私有目录、不承诺导出文件或私有模型已支持。
- 控制面仅使用主后台，避免本地镜像与主后台指令互相覆盖。不改变既有事件双传。
- 管理状态按词语生效：启用/停用/删除。删除保留决策标记，防止重复上报复活；原始上报明细保留供审核。移除个人加权不等于屏蔽公共词库里同名正常词。
- 无读音词条保留可见但不伪造拼音或使用次数。按现有可信读音与编码匹配恢复，不改变解码、过滤、整句规则。
- 服务器接收和手机应用确认分开记录；离线显示等待同步，不伪报即时生效。

## 任务 1：服务器同步协议和后台管理（先红后绿）

文件：`server/migrations/016_personal_dictionary.sql`、`server/src/api/personalDictionary.ts`、`server/src/api/personalDictionary.test.ts`、`server/src/app.ts`。

1. 编写真实路由测试：未绑定隔离、绑定保留来源、重复/过时批次不覆盖新值、停用/删除不复活、错误凭据拒绝、未确认显示等待、过时确认拒绝。
2. `cd server && npx vitest run src/api/personalDictionary.test.ts`：先确认缺少端点失败。
3. 最小数据库与路由实现：设备凭据/分组、按设备的词条快照、词语决策；每批最多 500 条，按单调序号幂等更新，下载完整快照带内容版本，应用后确认。
4. 同命令通过后运行 `npm test` 和 `npm run build`。不对生产库自动执行迁移。

## 任务 2：后台独立个人词库页面

文件：`client/src/api/personalDictionary.ts`、`client/src/views/PersonalDictionary.vue`、`client/src/main.ts`、`client/src/App.vue`、`client/tests/personal-dictionary.test.ts`。

1. 测试页面接入、按设备查询、分页搜索、状态更改与绑定确认。
2. 使用现有后台登录/设备选择器，不重写现有常用语与聊天页面。区分原始上报与有效词库；展示来源和未知值。
3. `./server/node_modules/.bin/vitest run client/tests/personal-dictionary.test.ts`、`cd client && npm run build`；有浏览器条件时补交互验收。

## 任务 3：手机本机与远端学习分离

文件：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/collect/{LocalInputStore,PersonalDictionarySync,DataCollector}.kt` 与相应 `src/test/.../collect/` 测试。

1. 先写 SQLite 升级、跨手机恢复、重复恢复权重不增加、远端记录不导出、删除不复活、敏感词过滤测试。
2. 数据库增量升级，保留既有队列、本机学习次数；独立远端层与决策表。查询合并但本机导出不含远端，计数不会在设备间循环。
3. 复用现有同步唤醒/后台任务，主后台注册凭据、分批上报、完整下载事务应用、最后确认版本；拒绝或网络错误保留本地数据。
4. 运行新测试及现有 LocalInputStore/个人学习/系统迁移/整句/T9 回归，编译 SDK。无真机结果时明确报告，不自动安装。

## 任务 4：验收与交付

- 核验新旧手机明细可区分、默认隔离、后台绑定、重复/过时上报、删除后重导入、离线确认、撤销采集同意、DB升级及既有输入测试。
- 保留工作区原有未提交改动，不自动部署、安装或提交。
- 文档记录实际命令/结果与未覆盖项；不把计划当完成证据。

## 换机身份与系统备份边界（实施中补充）

- 系统恢复可能复制设备 UUID，导致两台手机被后台当成同一台。新增 `InstallDeviceIdentity.kt`：首次升级保持原 UUID；新版保存偏好标记与 `noBackupFilesDir` 锚点，恢复到新手机只有标记而没有锚点时生成新 UUID。
- `app/src/main/res/xml/personal_data_{backup,extraction}_rules.xml` 排除本机学习/待传来源库、词库同步凭据与系统词典迁移状态；保留键盘其他配置的系统备份。否则复制的旧点击会以新手机身份再次计入共享权重，旧“迁移完成”标记也会错误阻止读取新手机公开词典。
- 此路径要求旧手机先升级本版、完成个人后台备份；旧系统备份或不遵守 Android 排除规则的厂商克隆不保证识别。原机数据不删除；未成功上报的个人数据不能凭空在新机恢复。
- 不拷贝、合并 Rime 的私有原生模型文件；本次同步的是 App 已保存的个人词条和选择证据。停用/删除只移除该个人补充层的召回/加权，并非全引擎禁词。

## 本轮实施与验收记录

状态：已实现并完成以下本地验证；**未部署生产数据库/API/网页，未安装到手机，未完成双真机端到端验收**。

- Server：新增独立同步凭据、按设备来源快照、默认隔离、后台显式绑组、按词合并/按来源明细分页搜索、启用/停用/删除标记、手机版本确认。类型错误的输入码也会被拒绝，避免污染整个下载快照。
- Client：「个人资产 → 个人词库与换机」，设备型号/编号、读取迁移状态、上报/应用时间、明细/合并视图、筛选与批量管理。未提供解除绑定；确认框说明整组合并，使用前核对设备。
- Android：SQLite v4→v5（v3回归仍通过），本机原始表与远端恢复表分离；保留旧学习和待传队列。已有同步唤醒链路串接主后台词库同步；凭据持久化、原始增量变化分批上传、快照事务应用后确认；网络失败不清空本机数据。
- 原候选解码/门禁/整句/拼音显示文件没有本轮差异。个人排名仍使用现有衰减算法，增加的只是明确绑定设备的来源证据。

### 实际验证

1. `cd server && npx vitest run src/api/personalDictionary.test.ts src/api/deviceIsolation.test.ts src/api/mobileReports.test.ts src/api/deviceDirectory.test.ts src/lib/dashboardAuth.test.ts`：34通过，1项真实PG用例在该命令中按条件跳过。
2. 单独启动隔离的本机临时 PostgreSQL 14，设置 `DICTIONARY_TEST_DATABASE_URL` 运行 `personalDictionary.test.ts`：9通过，包括真实事务回滚和重复迁移。测试结束已停止该临时实例，未访问生产数据。
3. `./server/node_modules/.bin/vitest run client/tests/personal-dictionary.test.ts client/tests/dictionary-backup.test.ts`：3通过；页面测试编译真实Vue组件并驱动事件，备份测试为资源接线检查，不冒充真实系统备份验收。
4. Android 独立构建目录 `build/dictionary-sync-audit` 的 Robolectric 回归：21套件120项，0失败。包括换机身份、网络顺序/拒绝坏快照、数据升级、重复恢复不加权、删除不复活、系统迁移、个人学习与 T9 旧问题。初次新增测试在API28使用SQLiteOpenHelper的AutoCloseable扩展导致测试夹具ClassCastException，已改为显式finally关闭；没有通过提高测试API或修改候选规则掩盖问题。
5. Server `npm run build`、Client `npm run build` 成功；网页仍有原有大包警告。
6. `:app:assembleOfflineDebug` 成功，APK v1/v2签名验证成功；`git diff --check` 无输出。
7. 最初全量 server 基线并非全绿：当时表情资源版本不一致、两个表情测试超时、另一任务的 groupedEdits 模块尚未出现。未为词库功能修改这些无关逻辑，也不据定向回归宣称全仓全绿。
8. 只读审查覆盖新增 server 路由，未发现有充分证据的重要问题；审查未覆盖其他端，不替代以上测试或真机验收。

### APK与交付边界

- `android/YuyanIme/app/build/dictionary-sync-audit/outputs/apk/offline/debug/yuyanIme_2026091612_debug.apk`
- 包名 `com.yuyan.pinyin.offline.debug`，versionCode `2026091612`，大小129754549字节。
- SHA256：`55341c93057885e4c0eca1aa49372982c519c67d0c7beb5e798ee9ee106ea391`。
- 当前工作区还有用户/其他任务的修改，APK包含构建时的工作区内容，不声称仅包含本功能。
- WSL 的 `adb devices -l` 当前没有在线设备，未执行安装、聊天操作或真实换机恢复。
- 使用与部署顺序见 `docs/guides/personal-dictionary-sync.md`；旧手机先覆盖升级并完成备份，后台部署后再进行设备绑定。
