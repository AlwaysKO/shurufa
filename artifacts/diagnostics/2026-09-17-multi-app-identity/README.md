# 多 App 会话身份识别（2026-09-17）

## 范围与实现

- 微信、QQ、抖音复用 `ConversationTitleStabilizer`。QQ/抖音仍读取端侧无障碍标题；微信空树入口保留端侧 ML Kit OCR。没有调用云端 OCR。
- 同一帧获得正文截图与标题字形；会话标识不直接由识别出的名字决定。连续至少两帧确认显示名，正在输入/在线不参与确认，截断名保持待确认；不进行编辑距离、繁简字或近似名称合并。
- 首张有效截图先保存，再有限补看确认名字；离开页面停止确认，不撤销已经保存的第一张。平台/账号作用域与导航版本隔离，迟到结果不确认新页面。全屏截图回调必须仍在原聊天窗口，避免跨 App 串帧。
- 同一连续页面中完全相同的未知帧复用待确认身份；导航后清空。确认名称复用原图片指纹，持久化重放只补身份信息，不重复图片记录或重新等待可能已清理的已上传原图。
- 通知仅在系统提供可靠会话 shortcut 时确认身份，否则明确待确认；不同通知线程隔离，不凭同名与截图会话合并。原有通知补偿支持范围不扩大。
- 服务端保护已确认名称、类型及置信度不被迟到的 pending 降级；原微信 `screenshot-v2:` 指纹格式保持兼容。

## 安全边界与限制

- 没有删除、回写、自动归并历史会话或用户记录；没有接入业务数据库跑测试，没有安装真机、发布服务或提交 Git。保留并行开发的素材、QQ 适配及采集调度改动。
- 新采集标识与旧记录分开，升级后可能有独立的新会话。完全同名字形、字体/主题变化或缺少真实联系人 ID 等情况不能保证百分百识别，不确定时宁可待确认，不强制合并。
- 本地模拟回归不能代替微信/QQ/抖音具体版本上的真机验收；需安装新版 APK、更新服务端后由用户操作验证。
- 本轮涉及路径见 `changed-files.json`；完整红绿测试、构建及类型检查日志保留在本目录。

## 最终验证

- Android：27 个测试类、162 项测试通过，0 失败/错误/跳过；范围为 `data.capture.*` 和 `service.capture.*`，详见 `android-complete.log` 及 `verification-summary.json`。
- 服务端：4 个 pg-mem/临时文件夹测试文件、33 项测试通过；`tsc --noEmit -p server/tsconfig.json` 通过。详见 `server-final-verified.log`、`server-types-verified.log`。未运行依赖业务库的测试。
- APK 构建成功，v1/v2 签名验证通过，DEX 检查包含新的共用判定类及命名空间。签名工具保留原 META-INF 的 JAR 元数据警告，未为消除警告修改签名配置。
- APK：`android/YuyanIme/app/build/multi-app-identity-audit/outputs/apk/offline/debug/yuyanIme_2026091718_debug.apk`
- SHA-256：`6f3501b77681770a7a4e1af8f23d71afbfa6e422b0dfdf7714e3f4e611aa667c`
- `git diff --check` 通过；本轮 25 个代码/测试文件及独立构建、诊断产物属主检查记录见 `ownership-check.txt`。

复现 Android 测试/构建（ko 用户，先 source `/home/ko/android-tools/env.sh`，进入 `android/YuyanIme`）：

```sh
./gradlew --offline --no-daemon --max-workers=2 \
  -I ../../artifacts/diagnostics/2026-09-17-multi-app-identity/build-isolation.gradle \
  :yuyansdk:testOfflineDebugUnitTest \
  --tests 'com.yuyan.imemodule.data.capture.*' \
  --tests 'com.yuyan.imemodule.service.capture.*' :app:assembleOfflineDebug
```

服务端在项目根目录仅运行：

```sh
./server/node_modules/.bin/vitest run server/src/chat/chatRepository.test.ts server/src/chat/assetStorage.test.ts server/src/api/chatCapture.test.ts server/src/api/chatDashboard.test.ts
./server/node_modules/.bin/tsc --noEmit -p server/tsconfig.json
```

