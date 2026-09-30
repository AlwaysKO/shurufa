# 导航截图记录 Implementation Plan

> **For Claude:** 使用 superpowers:executing-plans、test-driven-development 与 verification-before-completion。用户已确认下述设计，在当前分支执行，不创建分支或 worktree；不自动提交、推送、部署或安装。

**Goal:** 自己手机上的百度/高德出现路线总览时暂存一图，确认开始导航后持久保存并上传，在后台查看起终点和原图。

**Architecture:** 独立导航页面解析器和行程状态机接入既有无障碍服务；复用截图基础模块及输入空闲/Wi-Fi 上传守卫。独立原子文件队列保存已确认记录，由既有同步调度补传。服务端独立导航表原子保存元数据和限额图片，登录后的后台按设备分页查看。

**Tech Stack:** Kotlin / Android AccessibilityService / OkHttp / Express / PostgreSQL / Vue 3。

## 已确认设计与边界

- 2026-09-30 用户确认自动截图并在现有后台查看，随后确认「开始导航后才保留」。不将查路线标成出行，不将导航开始标成已到达或真实行驶轨迹。
- 手机设置新增独立默认关闭的导航记录开关，首次说明截图、起终点、线上上传；个人数据同步总开关仍有效。
- 仅允许百度与高德的前台页面；同时确认起终点、路线规划特征后暂存。起点为「我的位置」时如实保存，不凭空补地址。
- 导航开始需要近期开始按钮事件和导航中页面证据，或明确导航中页面与同一目的地匹配；不只凭「开始导航」文字判定。暂存超时、换App、锁屏、退出规划或切换起终点时失效；连续导航事件去重，新行程可再次记录同路线。
- 输入忙时不截图/编码/上传。截图失败不提交空记录；持久化失败不推进成功状态。用户后续明确所有截图仅Wi-Fi且不得影响其他应用；统一改为Wi-Fi且熄屏补传，亮屏主动取消，失败保留，使用记录ID幂等确认后移除。
- 后台菜单/路由纳入源码，显示来源、开始时间、起终点、总览截图。图片访问与列表一样登录并按设备隔离。数据不加入Git。
- 真机适配未知：当前只有模拟器，未安装百度/高德；合成页面测试不能代替真实版本验收。

## Task 1：服务端与数据库

文件：`server/migrations/038_navigation_records.sql`、`server/src/api/navigationRecords.ts`、`server/src/api/navigationRecords.test.ts`、`server/src/app.ts`。

1. 编写失败测试：参数/图片验证、重复上传、同ID冲突、设备隔离、图片鉴权、关闭保存。
2. `cd server && npx vitest run src/api/navigationRecords.test.ts` 确认红灯。
3. 实现带限额图片的原子记录、幂等回执、列表和图片接口；复用身份/保存开关，设备删除级联。
4. 重跑测试与 `npm run build`；只对已核验的本地数据库执行新增迁移并查询表。

## Task 2：Android 解析、状态机与持久队列

文件：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/navigation/` 与对应 `src/test/`；修改截图模块、无障碍服务、同步入口与设置页。

1. 失败测试覆盖：两平台起终点、只有搜索/模拟导航不确认、规划后取消、导航开始、重复事件、超时、换目的地、同路线新行程、存储失败重试。
2. 运行 `:yuyansdk:testDebugUnitTest --tests '*Navigation*'` 确认失败后实现最小解析与状态机。
3. 原子队列和回执验证测试先红后绿；接入输入空闲、窗口校验、关闭开关、生命周期取消。
4. 运行定向单测、截图模块回归及 `:app:compileOfflineDebugKotlin`。

## Task 3：后台与整体验证

文件：`client/src/views/NavigationRecords.vue`、`client/src/api/index.ts`、`client/src/main.ts`、`client/src/App.vue`。

1. 加入导航记录菜单、来源筛选、分页、图片放大；切换设备清空旧结果并拒收过期请求。
2. `cd client && npm run build`；本地登录/API验证列表、图片和用户隔离。
3. 依 requesting-code-review 发起独立只读审查并处理问题；复跑受影响测试。
4. 用 `.runtime/macos/build-apk.command` 原签名打包、核对版本与SHA256。Mac交付 `apk/`，注明无法复制 Windows E 盘；不安装、不部署。
5. 验证记录写入 `docs/testing/2026-09-30-navigation-records.md`，明确自动化通过范围和真机待验收项。

## 执行结果

- Task 1–3 本地实现与验证完成：服务端/认证15项，Android最终58项，前后端构建、Android编译、浏览器真实接口与设备切换竞态验证通过。
- 本机新增迁移已执行，原签名APK已生成。实际地图App页面和整机性能尚待真机验收，不能将合成节点测试当成兼容性证明。
- 用户后续「所有截图仅Wi-Fi且不能影响其他应用」已落实到共享上传入口与AGENTS；亮屏暂停截图上传，普通文字同步保留原规则。
- 未提交、推送或部署。交付路径、哈希及具体限制见上述验证记录。
