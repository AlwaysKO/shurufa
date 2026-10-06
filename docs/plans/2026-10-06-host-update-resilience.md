# 微信与抖音更新后的截图兼容补强

> 执行：按 systematic-debugging、writing-plans、TDD 与 verification-before-completion 完成；沿用用户已批准的下发规则、多重识别与保守兜底方向。当前分支保护已有修改，不自动提交或部署。

**目标：** 现在增强低维护兼容能力，不以记录长期要求替代实现。
**架构：** 复用现有 schemaVersion=1 规则与最后有效缓存、同页多证据、截图持久队列及上传链路。补齐本地宿主版本刷新与无固定类名的语义结构回退；不增加轮询联网或任意页面截图。
**技术栈：** Kotlin、Android AccessibilityNodeInfo、现有 Kotlin/JUnit/Robolectric 回归。

## 判断与取舍
- 已有远端可配置 title/input/body ID、返回/聊天设置/语音标签与版本范围；不是重新搭一套配置。
- 只增加 ID 映射仍需每次手工修补；直接通用 OCR/任意页面截图既耗电又可能误采。采用控件语义加同层聊天证据的回退，结合已有可下发规则。
- 官方节点支持 isEditable/isScrollable/isPassword/isVisibleToUser；这些字段与具体类名独立，但不能单独证明聊天页面。依据 https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo 。
- 任何第三方全新机制或安全窗口变化都无法预先保证兼容；必须准确记录未覆盖边界。

## 任务与验证
1. 结构回退：UiNodeSnapshot、AccessibilityTreeReader、DouyinChatAdapter、WeChatChatAdapter。先增加合成结构测试，未知字段兼容解码证明旧代码拒绝自定义语义控件；再传递节点语义与可见性，保留明确页头、同层唯一输入、消息区和负例限制。截图签名包含影响识别的语义状态。
2. 版本恢复：ChatCaptureRefreshController、ChatCaptureSettings、PassiveChatAccessibilityService。先失败测试离线版本变更、限频、进行中网络不阻塞本地恢复、正确版本缓存选择，再分离本地恢复和联网批次。尊重输入/游戏/同意守卫，不主线程查PM，不额外联网。
3. 回归：ID全换/移除、自定义控件、额外嵌套、缩放；评论/搜索/视频/密码/不可见/跨层证据必须拒绝；保留旧40.5/40.6及微信截图、去重、队列上传测试。执行 Android capture/service.capture/相关collect 定向回归，git diff --check。
4. 交付：原签名非testOnly APK在Mac项目apk目录，核验版本、签名、SHA。可连接真机时覆盖安装并检查服务/版本；用户操作聊天，助手不代发消息。记录实际真机验收与合成变更测试区别。

## 用户纠正
本次明确是低维护的软件机制需求；上轮仅写AGENTS验收要求不满足目标。后续用户说“记住”时结合上下文判断，不能把功能诉求降为文档。
