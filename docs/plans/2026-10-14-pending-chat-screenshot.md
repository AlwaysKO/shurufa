# 待确认聊天必须有真实截图

用户要求：待确认会话中仅有“[动画表情]”等占位内容的记录，不截图不上报；必须能确认截到聊天画面，否则舍弃。

## 已定位

共享 `UnconfirmedTextPolicy` 原本仅过滤 text，并对截图失败、延后截图和通知媒体标记直接放行，导致 sticker 等通知占位内容进入待确认会话。通知截图补偿和通知文字是独立路径，发起截图请求不代表已经拿到截图。

## 实施和验证

1. 在共享规则、新采集 Coordinator、Room 旧待传和普通报告旧待传添加无图 sticker 回归，先确认失败。
2. 待确认/截断/低置信身份只保留实际 image 附件且来自现有聊天截图路径的消息；通知缩略图、失败/延后标记不构成截图。已确认文字保持原规则。截图来源仍通过原有页面、窗口、权限及输入/游戏守卫，不新增强行打开聊天或盲目截图。
3. 更新旧测试中已被用户新要求取代的预期；保持真实截图依赖、网络失败重试和去重记录，过滤不是伪造远端保存回执。
4. 回归微信/抖音页面识别与通知补截图、截图/队列传输测试，独立审查后打包原签名非 testOnly APK 至 Mac `apk/`。

## 进一步定位与处理

- 微信空树兜底此前将缺少识别器的默认身份也视为聊天；现在待确认/截断身份还要求实际识别器给出聊天判断，并由截图像素识别到输入栏。通知补截图采用相同条件，明确的结构化聊天页面可走原截图流程。
- 通知页面判断此前仅检查 `conversation_screenshot`，会把同样使用该标记的朋友圈页面误作聊天。现在只接受对应平台的聊天来源；其他应用页面仍归原独立页面采集。
- 新增门禁先验证失败：第一轮四类队列/规则回归新增4项失败，输入栏证据与通知兜底新增2项失败，普通空树与非聊天页面新增2项失败，再完成对应修复。
- `WechatListCaptureTest` 首个断言期望旧聊天路径保存“微信”列表，但当前 HEAD 的 `WeChatChatAdapter.parseFixedPage` 已明确将“微信/发现”交给独立页面并返回 Skip；属于原有测试与实现不一致，本次不恢复旧聊天列表路径。
- 只读独立审查未发现阻塞项。旧附件引用但原文件已丢失的队列可能在读取附件时继续重试，本次不扩大附件清理范围；无图占位消息的直接及生产原子交接路径另有覆盖。
- 通知媒体旧暂存也在解码前执行同一规则，舍弃未确认通知缩略图，避免反复解码；只结束该任务自有暂存，不触碰通知源 URI 或共享截图。已确认通知仍保留导入失败后恢复 PNG 的流程。

## 范围

修改正式源码，不修改 `.runtime` 交付快照。不自动删除线上历史、不修改线上配置或数据库；新规则需安装新 APK 才对手机新记录和既有待传生效。当前分支直接开发，不提交或推送。

## 验证记录

- `:yuyansdk:testOfflineDebugUnitTest --tests 'com.yuyan.imemodule.data.capture.*' --tests '*EventDeliveryPendingTextTest'`：694 项通过、0 失败、0 跳过，包含各平台识别、截图媒体、独立页面、Room 直接/原子交接、通用报告过滤以及网络失败重试。使用 JDK17、离线 Gradle 和 Mac `/private/tmp` 测试目录。
- 截图服务回归：`PeerTypingCaptureTest`、`ChatContentDedupCaptureTest`、`NotificationScreenshotFallbackTest`、`ScreenshotWindowReadThreadTest`、`WechatScreenshotBoundaryTest`、`ScreenshotConfirmationCaptureTest`、`NotificationMediaRecoveryTest`、`ScreenshotUpdatePolicyTest`、`EmptyTreeScreenshotBoundsTest` 共 41 项通过、0 失败、0 跳过。既有空白截图夹具补上真实像素输入栏；候选节流使用可控时钟，未放宽生产判断。
- 合计 735 项相关回归通过；另已知历史列表测试失配如上，不将本轮结果称为全库测试全通过。没有连接真机或执行线上端到端验证。

## APK 交付

- `:app:assembleOfflineDebug -Pandroid.injected.testOnly=false --offline` 构建成功。
- Mac 安装包：`apk/shurufa-2026-10-14-v20261014.23-2026101423-debug-171c2e0b.apk`；版本 `20261014.23` / `2026101423`。
- SHA256：`171c2e0bab240d35619c1d57b06263faa5b16f9dbefdd3c0528889def025dcd3`，交付文件与构建输出一致。
- `tools/verify-delivery-apk.py` 核验 API23/27/28/32/36 均为原证书，且非 testOnly。未安装手机，未提交/推送，未修改线上配置。
