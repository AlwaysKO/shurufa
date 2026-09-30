# 导航截图记录验证（2026-09-30）

## 交付行为

- 手机「设置 → 其他 → 导航记录」单独开启，默认关闭；Android 11+，受个人数据同步总开关与无障碍服务控制。
- 支持识别百度、高德前台页面中明确的起终点与路线规划特征。只查路线时暂存内存图片，最长5分钟；确认导航中页面且有近期开始点击或匹配目的地证据后保存一图。
- 后台「内容记录 → 导航记录」，路由 `/navigation-records`；设备隔离、地图来源筛选、每页20条、原图弹窗。
- 导航开始不表示到达，不是实际行驶轨迹。起点显示「我的位置」时按原样记录。
- 只裁取地图窗口；截图与编码使用输入空闲和共享准备许可。已确认图片在App私有目录原子持久化；只在Wi-Fi、熄屏且输入空闲时上传线上主地址（聊天截图共享该规则，普通文字同步不扩大熄屏限制），关闭导航或总开关会暂停。回执ID与哈希匹配后删除本地待传文件；失败退避30秒且不阻塞其他记录。
- 图片和元数据存入同一 `navigation_record` 行；图片接口需要后台登录并按设备查询，禁止缓存。重复ID内容冲突返回409。设备删除通过外键级联清除其导航记录。

## 自动化验证

- 服务端及后台认证：15项通过（导航6项、认证9项），无跳过。导航在本机 PostgreSQL 临时独立 schema 中测试，结束仅删除该测试 schema。覆盖图片真实解码与原字节读取、重复上传、同ID冲突、错误平台/数组、时间/名称校验、保存关闭、设备隔离、未登录拒绝、第二页排序和设备外键级联。
- Android：22个测试类、58项通过，0失败/错误/跳过。覆盖导航解析/状态机/原子队列/撤回上传/窗口裁剪，并回归截图线程、图片调度、输入优先取消、聊天服务配置及上传器。另验证亮屏时阻止图片但保留纯聊天元数据原同步时机，逐块上传中亮屏中止，熄屏时仍拒绝蜂窝/未验证Wi-Fi/撤回同意/输入忙。
- 前后端生产构建及 Android 编译通过。前端保留既有的大包体积提示。
- Playwright 通过本机开发站的真实登录与导航接口验证菜单/路由、列表、图片加载、原图弹窗与Esc关闭，以及A设备慢响应在切B后不得写回。使用合成纯色测试图片，测试设备与记录已经清理；截图及脚本仅留 `artifacts/diagnostics/2026-09-30-navigation/`，不提交Git。
- 新增迁移038已在核验过的本机 `127.0.0.1 / personal_ime` 执行，核对了12个字段；未操作线上数据库。
- 独立代码审查发现并修复：非字符串平台校验、路线变化后旧图误记、上传中关闭开关仍继续、最老失败记录堵住队列；进一步修复同路线时间标签刷新时丢失开始点击凭据。

可重复的验证命令（项目根目录）：

```bash
source scripts/lib/env.sh
load_dotenv .env.local
NAVIGATION_TEST_LOCAL=1 server/node_modules/.bin/vitest run server/src/api/navigationRecords.test.ts server/src/lib/dashboardAuth.test.ts
source .runtime/macos/android-env.sh
android/YuyanIme/gradlew -p android/YuyanIme :yuyansdk:testOfflineDebugUnitTest --tests '*Navigation*' --tests '*WindowScreenshotter*' --tests '*GuardedChatBody*' --tests '*ImageUpload*' --tests '*InputPriorityCancellation*' --tests '*ChatCaptureLifecycleConfig*' --tests '*EventDeliveryImagePolicy*' --tests '*ScreenshotUploadDeviceIdleTest' --tests '*CaptureUploaderTest'
```

## 尚未验收与运行边界

- 当前仅连接 `emulator-5554`，未安装百度/高德；解析器测试使用合成节点，不能证明任意真实地图版本都提供这些无障碍标签。真实手机须分别验证规划页、导航页、模拟导航排除、返回取消和同路线二次出发。
- 地图若未暴露足够的起终点/导航状态，或路线页停留太短、正在打字、窗口变更/锁屏、系统拒绝截图，本次可能漏记。首版未加入OCR猜测或后台还原路线；不会伪造起终点或把局部导航画面称为路线总览。
- 屏幕图片是否完整展示实际选中路线仍需真机视觉验收；自动化节点判据只确认规划页面结构。
- 本次未部署线上、未安装手机、未提交或推送Git。手机默认指向线上主后台，因此使用前还需部署对应服务端/前端及迁移038；旧服务端404时客户端保留待传记录。
- 工作区同时存在其他会话的通话录音改动，已保留；APK按当前工作区构建，本轮未替其他改动作功能验收。
- 2026-09-30 用户追加「所有截图仅Wi-Fi且不能影响其他应用使用」已写入AGENTS。统一图片上传要求熄屏，首次熄屏可能等现有轮询约30秒；系统节电还可能延迟。该限制不反向阻止正常亮屏截图，也不扩大到普通文字同步。
- 本机为Mac，固定Windows `E:\Projects\shurufa-android\apk` 不可访问，使用本机 `apk/` 交付。

## 打包

- 本轮唯一推荐：`/Users/pj/project/shurufa/apk/shurufa-2026-09-30-v20260930.22-2026093022-debug-992f1e49.apk`。包含最终Wi-Fi/熄屏统一规则；此前本轮生成的 `0daf683f` 包已被此包取代，不删除历史文件。
- 版本：`20260930.22` / `2026093022`，debug、非testOnly；原签名在API23/27/28/32/36验证通过。
- SHA256：`992f1e490a6582e2b13a5fdd267c0e55a5de10486fbe76770f31ff65784708ee`。
- 签名证书SHA256：`a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。
- 未安装任何手机或模拟器，未完成Windows E盘交付。
