# 微信隐藏通知内容截图兜底实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 在用户关闭微信“通知显示消息详情”时，以汇总通知作为新消息信号，安全地截取当前微信页面一次并上传。

**架构：** `PassiveNotificationListener` 识别隐藏内容的汇总通知，通过进程内桥接通知 `PassiveChatAccessibilityService`。无障碍服务延迟合并触发，仅在屏幕解锁、微信前台且输入法未展开时截取聊天内容区。截图以资源哈希去重，归入“微信（截图兜底）”未识别会话，不伪造联系人身份。

**技术栈：** Kotlin、Android NotificationListenerService/AccessibilityService、Coroutines、Room 采集队列、Robolectric/JUnit。

---

### 任务 1：识别截图兜底触发

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/notification/NotificationParser.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/notification/NotificationParserTest.kt`

1. 先测试微信汇总通知是兜底触发，普通内容通知不是。
2. 验证测试失败。
3. 增加最小判定 API，复用已有汇总通知规则。
4. 验证测试通过。

### 任务 2：延迟合并与安全截图

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/NotificationScreenshotFallback.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/service/capture/NotificationScreenshotFallbackTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveNotificationListener.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveChatAccessibilityService.kt`

1. 先测试连续汇总通知合并为最后一次触发。
2. 验证测试失败。
3. 实现进程内桥接和可取消延迟；在截图前校验前台包名、锁屏和输入法窗口。
4. 截取排除底部输入区的聊天区，使用 WebP 压缩和资源 SHA-256 去重。
5. 验证相关单测。

### 任务 3：打包和真机验证

1. 运行采集相关单测。
2. 用已安装 APK 相同签名构建 `offlineDebug` APK。
3. `adb install -r` 保留数据安装。
4. 验证版本、通知监听、无障碍服务和后台无效汇总数据。
