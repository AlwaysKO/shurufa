# 微信 GIF 原文件分享适配计划

> 使用 superpowers:subagent-driven-development、test-driven-development；实现→规格→质量依次审查。main 原目录，不创建 worktree。

**目标：** 避开已经真机复现会静态化的微信 commitContent 路径；原 GIF 字节通过标准分享入口交付，不能把“打开分享”谎报为“发送成功”。

**已知与边界：** 20260907.17 中原 16 帧 GIF 经候选直发，微信保存为单帧 PNG；系统授权 URI 指向原 GIF。同一原 GIF 从微信相册发送能够动态播放（视频已归档）。标准 ACTION_SEND 的 ADB 探针预览失败并取消，尚非应用内分享验证；新入口必须再真机测试，不能预先宣布动态发送问题已验收。用户允许拔掉手机后离线继续修复；已说明方案会多一步选择聊天，异步询问偏好，目前按最小不引入 SDK 的方案推进。

**方案取舍：** 首选 Android ACTION_SEND，原 FileProvider URI、image/gif、EXTRA_STREAM、ClipData 和临时读取授权，仅定向 com.tencent.mm，不使用微信私有组件/参数或假冒其他应用 AppID。保留现有原字节相册 fallback；不把 fallback 当成一键直发。微信 SDK 方案需额外平台注册与签名配置，本轮不引入。不能重新编码或改后缀规避问题。

## 任务1：微信 GIF 分享与交接结果（TDD）

文件：
- `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/send/ExpressionContentSender.kt`
- 同目录 `ExpressionSendController.kt`、`ExpressionFlowController.kt`
- `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- `android/YuyanIme/yuyansdk/src/main/res/values/strings.xml`
- `expression/ui/ExpressionSendDialog.kt` 与 `keyboard/container/SymbolContainer.kt` 的新增结果穷尽分支/避免重复fallback。
- 对应 Sender、Controller、Flow、InputView 测试。

1. RED：微信+GIF启动ACTION_SEND而不commitContent，URI读回与原文件字节相同、MIME与EXTRA_STREAM/ClipData一致、带NEW_TASK和GRANT_READ_URI_PERMISSION且setPackage微信；非微信GIF与微信静态图仍走原commit。
2. RED：分享启动失败不再回落到会静态化的commit；Flow沿用相册fallback；新增 `ShareOpened`（或明确等价名）不触发fallback，清理发送控制状态。此结果不是Sent。
3. GREEN：只改上述路由，不加SDK、不尝试自动选择聊天或确认发送；在分享路由不依赖宿主声明支持GIF，因为这是独立标准分享入口。启动成功提示“请在微信选择聊天并确认发送”。
4. InputView交接后结束busy并清已交接查询；失败走既有保存/错误处理；重复发送、取消、同词重试测试保持。
5. 定向测试RED/GREEN；规格→质量；中文独立提交。

## 验证

Gradle单一所有者。保留用户两行字号及另一任务6个未完成测试；沿用 `/tmp/shurufa-send-debug/exclude-unfinished-t9.gradle` 与 `test-heap.gradle`（1536MB），不称整个工作区全绿。运行已有 yuyansdk 套件与 `:app:assembleOfflineDebug`，恢复local.properties。手机已交还用户，本轮不再安装或操作；APK明确待真机验证。服务端及素材不变，不重跑生成链。记录最终测试、APK SHA和未验证项。

## 离线交付记录

- 实现 `bc0fb52`，规格与质量审查通过；定向79项、现有套件442项（71 suites）通过，明确排除另一任务6个未完成测试。
- `:app:assembleOfflineDebug` 成功，20260907.18；SDK配置与用户修改均保留。
- 新APK未安装、新分享路线尚未真机验收；不宣称微信动态发送已通过。详见 artifacts/expression-functional/wechat-gif/share-verification.json。
