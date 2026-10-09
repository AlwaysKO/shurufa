# 电话与微信自录补齐实施计划

**目标：** 修复只有普通来电触发、一次失败永久停用的问题，补电话呼出和微信明确通话状态下的自录，交付原签名 APK。

**架构：** 沿用前台麦克风服务、会话日志、系统录音优先去重及 Wi-Fi 上传。新版电话/微信范围需用户明确确认；旧授权继续仅来电。微信仅使用持续通话通知和通信音频模式共同确认，提供前台手动入口，不以普通聊天通知或语音消息启动录音。

**技术栈：** Kotlin、Android Telephony/MediaRecorder/NotificationListener、Robolectric、Gradle JDK17。

## 步骤与验证

1. 在 `CallRecordingServiceHealthTest`、`WechatCallSignalsTest`、`WechatRecordingNotificationTest`、`CallRecordingExpandedConsentTest`、`CallRecordingSettingsDisclosureTest` 写回归并观察失败。
2. 修改 `MultiSimIncomingCallPolicy` 和 `CallRecordingService`：呼出须先观察全部 SIM 空闲；OFFHOOK 不当成已接通，不伪造完整通话时长；并发通话中断；失败保留诊断且下一通可重试，同一通不循环抢麦。
3. 新建 `WechatCallSignals` 并接入已有 `PassiveNotificationListener`：排除 MessagingStyle、旧通知及其他应用；仅新鲜精确持续通话状态触发。服务在 30 秒候选窗口内检查通信模式，录音时每秒检查，空闲保持 30 分钟兜底。通知结束、通信模式结束、撤权或电话冲突即停止。
4. 修改 `CallRecordingConsent`、`CallRecordingSettingsActivity`：扩大授权范围须重新确认，显示真实录音/上传含义、失败原因、停止入口；手动微信入口只由前台操作发起。
5. 运行录音、通知及相关回归，审查失败恢复、误触发和撤权边界。执行 `:app:assembleOfflineDebug -Pandroid.injected.testOnly=false`，使用 `tools/verify-delivery-apk.py` 验证原签名和可独立安装，交付项目 `apk/`。

## 边界

- 用户 2026-10-09 表示暂不方便连接手机，先完成代码和安装包；本轮不宣称真机双方收音或上传验收通过。
- 普通第三方应用仍受 Android 麦克风并发限制，不使用特权/隐藏 API 绕过。接口启动、有音轨、有振幅都不等于双方声音已验证。
- 微信若不发布可识别通知，自动触发不可保证；提供用户主动确认当前微信通话的手动入口。
- 原系统录音只读保留，已有去重、7 天范围和上传回执后的自录清理规则不变。不修改线上数据库、不自动提交或推送。

## 本轮结果

- 已实现来电/呼出及微信通知与音频状态联合触发；手动微信入口可明确选择语音/视频。失败原因保留，下一通恢复尝试；旧授权不会扩大范围。
- 额外回归覆盖旧 Android 单/双参数通知移除、授权降级立即停止、电话接替微信不覆盖状态、注册与销毁交错的监听清理。独立代码审查发现的问题已修复并复核。
- JDK17 执行 `:yuyansdk:testOfflineDebugUnitTest --tests '*callrecording*' --tests '*Notification*Test'`：186 项通过，0 失败/跳过。新增用例先观察失败再修复。测试与构建日志仅本机 `/private/tmp/shurufa-call-delivery.log`。
- `:app:assembleOfflineDebug -Pandroid.injected.testOnly=false` 成功；交付 `apk/shurufa-2026-10-09-v20261009.08-2026100908-debug-c5a7cdf0.apk`，版本 20261009.08 / 2026100908，包名 `com.yuyan.pinyin.offline.debug`，arm64-v8a。
- 源 APK 与交付 APK SHA256 一致：`c5a7cdf09984cff6c861a88da3575eff75f106e3b02123501b7d342d12c5fee1`。原证书与非 testOnly 校验见交付验证，不卸载或清除手机数据。
- 用户当前不方便连接手机，本轮未安装、未验证双方真实声音或手机到线上的录音上传；这些不能由 Robolectric 通过推定。
