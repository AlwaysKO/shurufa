# 微信群红包助手实施计划

> 使用 writing-plans、executing-plans、test-driven-development 和 verification-before-completion；按用户全局规则在当前分支工作，不创建分支，不自动提交、推送或安装。

**目标：** 荣耀 200，无需身份验证解锁，用户接受短暂亮屏；仅处理微信群红包，排除私聊。默认关闭，由设置页独立开启。

**架构：** 复用 PassiveNotificationListener 和 PassiveChatAccessibilityService，在采集同意开关之前分发给独立的本地红包助手。通知仅是线索；进入微信后核对聊天页、群身份和会话名，再点击可识别的收到红包卡片与拆红包按钮。不读取支付密码、不发送消息、不新增上报。

**技术：** Kotlin、NotificationListenerService、AccessibilityService、非导出唤醒 Activity、JUnit/Robolectric。

## 方案与边界

- 采用系统通知 + 无障碍控件操作；与既有服务兼容，能够用真实页面验证。
- 单纯定时轮询无法可靠获取息屏聊天界面，且增加耗电，不采用。
- 修改微信或私有协议不在本次范围。
- 通知隐藏内容、免打扰无通知、微信未暴露控件时跳过；不根据固定坐标盲点，不承诺所有微信版本可用。
- 群标题人数标识与通知群标记仅用于候选筛选。领取前必须打开当前会话资料页，确认同时存在“群聊名称”“群公告”等群专属设置，再返回同一会话领取；单凭标题后缀绝不授权领取。
- 新事件有时效、去重、有限队列和任务超时。打字、滚动、切换应用/会话、锁屏或关闭功能时终止。仅在助手唤醒且没有检测到用户接管时尝试恢复息屏；公开 API 无法保证观察所有触摸。
- 现有聊天采集仍由自己的同意开关控制，自动领取不暗中开启采集。

## 实施步骤与验证

1. 新增 `data/redpacket/GroupRedPacketPolicy.kt` 与 `GroupRedPacketPolicyTest.kt`：先验证群/私聊、普通文字、过期通知、重复通知、会话切换、已领取卡片、超时及一次点击，运行定向单元测试观察 RED 后实现 GREEN。
2. 新增本地设置、通知提取及无障碍控制器；在两个现有服务中接入生命周期。通过 Robolectric 测试独立开关、通知适配及唤醒令牌，不改变原采集行为。
3. 新增非导出唤醒 Activity，使用系统亮屏与解除非安全锁接口，入口必须匹配当前任务令牌。设置页增加独立开关、权限入口和最近状态；不自动打开用户权限。
4. 运行新测试和通知/采集相关回归，独立代码审查，修复发现的问题。
5. 使用本机 `.runtime/macos/android-env.sh` 构建 `:app:packageOfflineDebug`，原证书验签、验证非 testOnly，复制到 `apk/` 并核对 SHA256。

## 真机验收

当前 ADB 仅连接模拟器，尚无荣耀 200。真机需确认系统与微信版本，依次验证：群红包、私聊不领取、息屏唤醒、连续通知、已领完、关闭开关、用户打字/切换应用以及恢复息屏。不得以单元测试或构建成功宣称真机领取通过。

## 实现记录

- 初始 RED 已确认新策略类缺失；首次编译发现 Android framework 没有 AndroidX 的 MessagingStyle 提取方法，已改为项目现有 `Message.getMessagesFromBundleArray` 用法（编译证据见本地 `/tmp/shurufa-redpacket-green.log`）。后续优先核对本项目已用的公开 API，不混用 AndroidX 与 framework API。
- 独立审查要求修复：标题人数不能证明群身份、同位置文案不能永久去重、自动点击回执不能吞掉同一时间段全部人工点击。增加群资料确认、可见卡片生命周期去重与节点匹配的一次性点击回执。

## 验证与交付（2026-10-04）

- 最终命令：加载 `.runtime/macos/android-env.sh` 后运行 `./android/YuyanIme/gradlew -p android/YuyanIme :yuyansdk:testOfflineDebugUnitTest --tests '*GroupRedPacket*Test' --tests '*NotificationParserTest' --tests '*NotificationEventDeduplicatorTest' --tests '*NotificationScreenshotFallbackTest' --tests '*WechatListCaptureTest' --tests '*ChatCaptureThreadingTest' :app:packageOfflineDebug -Pandroid.injected.testOnly=false --offline --console=plain`。69 项测试通过、零失败，构建成功；存在弃用 API 警告，不影响本次构建。
- 群资料验证新增回归曾在旧实现上断言失败，补入资料确认后通过。最终也覆盖连续同位置红包、有限重试预算、精确的一次性点击回执、独立开关、非群通知过滤、失效唤醒令牌及界面识别。
- 后台单线程读取界面，主线程操作前再次检查输入空闲、任务代次、窗口和节点身份；关闭或接管时使旧读取失效。此守卫不等于荣耀真机输入性能已经验收。
- 推荐文件：`apk/shurufa-2026-10-04-v20261004.13-2026100413-debug-e58270ef.apk`；包名 `com.yuyan.pinyin.offline.debug`，版本 `20261004.13 / 2026100413`。
- SHA256：`e58270efa0854d27bdf85c57813eeb3f16a475227e4a49b141a5541e2973569b`。源 APK 与交付文件一致；`verify-delivery-apk.py` 核验 API23/27/28/32/36 原证书及非 testOnly 均通过。
- 设置入口：其他设置 → 自动抢微信群红包（实验）；默认关闭，另有通知使用权、无障碍和系统应用设置入口。无需开启个人数据同步。
- 无稳定消息 ID 时，同外观卡片最多尝试 3 次/15 秒，避免微信不暴露已领取状态时循环操作；密集相同外观红包可能因此跳过。无法读取群资料或红包控件、通知内容隐藏及无通知的后台群均可能漏抢。
- 尚未连接荣耀 200，未执行手机安装、真实领取或息屏恢复验收；代码和 APK 为可验证实验版，不宣称所有 MagicOS/微信版本可用。未提交、推送或部署后台。
