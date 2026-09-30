# 截图后台补传、亮屏低速/熄屏加速验证（2026-10-01）

## 手机与线上证据

- 用户明确授权排查自己已连接手机。最初ADB只有模拟器，用户重新授权后识别荣耀 ELI-AN00，已装20261001.00/2026100100，02:17更新。个人数据同步及导航记录开关为true，检查时亮屏。
- 只读复制capture.db、local_input.db及WAL，SQLite quick_check均ok。只输出计数、时间、重试和文件存在性，不输出聊天正文、联系人或路线地址；诊断文件仅本地忽略目录。
- capture.db：65个pending_asset，attempts全部0；逐一核对65个原始图片文件均存在且非空。74个pending_message均为微信，captured_at覆盖北京时间9月30日22:48:49至10月1日03:42:17。
- navigation-outbox：1条百度记录，02:18:04截图，Base64长度318384，尚无成功回执标记。
- 线上通过SSH执行只读事务，仅查询该手机：65个待传图片hash在media_asset中匹配数0；navigation_record总数0，该待传导航ID也为0。最新带媒体微信消息captured_at=9月30日22:17:23，created_at=22:17:55。
- 通用报告队列另有2个线上待传chat_asset及2个chat_messages，尚未尝试。USB/旧镜像目标保留大量历史报告，不能把这些副本数量全部算成线上积压，也不能为补传绕过仅Wi-Fi/仅线上规则。
- 源码旧门禁在ImageUploadRuntime的资格/HTTP逐块校验及CaptureUploader的Room转存入口都要求熄屏。已生成但未传的证据与门禁吻合，不能将后台无图归因于没有截图。

## 新规则

- 微信/QQ/抖音聊天截图及百度/高德路线图共用：验证可用Wi-Fi+同意/对应开关+输入空闲；亮屏也可补传，停止输入3秒后恢复资格。
- 每块8KiB动态选速：亮屏等待256ms（约31.25KiB/s），熄屏16ms（约500KiB/s），包括JSON/Base64。共享滚动预算8/32MiB每分钟、图片开始间隔3/1秒；切档不清额度、不重建请求。
- 打字及键盘触摸继续异步取消在途请求；网络绑定Wi-Fi，逐块重新检查同意/输入/网络，失败保留待传。亮屏本身不再取消。
- 截图准备和截图网络上传分别单并发，已有慢上传不阻止新截图准备。普通文字同步不占截图上传许可；不新增主线程网络/磁盘工作。
- 5分钟call timeout容纳慢速大图，防止原90秒上限造成反复失败。保留既有新入队/网络唤醒、积压图片1秒检查、常规30秒同步及系统Job恢复。OS节电或进程停止可能延迟，不能承诺任何手机绝无影响或后台永不被限制。

## 测试与审查

- 先在旧门禁下运行亮屏资格、屏幕切换不中断、真实chat/assets及navigation请求构建：3项全部失败；删除门禁后通过。
- 熄屏更大预算测试先失败，再加入两档共享预算/速率和大图超时验证。
- 审查发现原共享busy可能使128秒慢上传挡住截图，新增测试先失败，再拆两个独立幂等许可；验证第二上传/第二准备被拒绝，释放准备不会误放行第二上传，输入忙两者拒绝。
- 真实prepareChatCall测试不发外网：亮屏请求可建、SCREEN_ON广播不取消、打字中断已有请求、失去已验证Wi-Fi资格中断；GuardedChatBody验证写入部分数据后暂停。
- 回归覆盖导航持久去重/待传、Room上传器、通用图片队列、输入取消/接线、静默上报守卫；22个测试类共71项，失败/错误/跳过均0。
- Android编译和client生产构建通过；client保留既有>500kB分块提醒。git diff --check通过。
- 最终独立只读审查未发现阻断：许可释放路径、逐块动态速率、输入/网络取消及请求超时边界均复核。

## APK交付

- 文件：`apk/shurufa-2026-10-01-v20261001.03-2026100103-debug-667a4832.apk`。
- 版本：20261001.03（2026100103）。SHA256：`667a483256a3e15809ba086600109bb4793972695ce2f43bdd4dea416a6d7218`。
- 打包成功；API23/27/28/32/36原签名验证通过，非testOnly；交付文件哈希已复核。Mac本机apk目录交付，不需要Windows E盘。

## 尚未验收

- 手机途中曾从ADB断开，交付前已恢复连接；只读复核仍为20261001.00（2026100100）。新包未安装，没有把这些积压成功补传或真机输入/其他App体验声明为已验证。
- 本轮未部署后台文字改动，未安装、Git提交或推送；线上只读诊断未修改数据。既有原图/待传队列未删除。
- 当前手机需要覆盖安装新包才能采用新调度，不能仅靠后台部署改变旧APK门禁。

## 重复验证命令

```bash
source .runtime/macos/android-env.sh
android/YuyanIme/gradlew -p android/YuyanIme :yuyansdk:testOfflineDebugUnitTest --tests '*ScreenshotUploadDeviceIdleTest' --tests '*ImageUpload*' --tests '*GuardedChatBody*' --tests '*InputPriorityCancellation*' --tests '*CaptureUploaderTest' --tests '*Navigation*' --tests '*EventDeliveryImagePolicy*' --tests '*SilentReportingTest' --tests '*InputPriorityWiringTest' :app:compileOfflineDebugKotlin --console=plain
(cd client && npm run build)
git diff --check
bash .runtime/macos/build-apk.command
```
