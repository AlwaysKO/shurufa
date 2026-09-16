# 前台聊天截图触发实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 微信聊天截图不再依赖应用通知；自己发送以及已打开聊天页中的新回复，都能触发截图，同时保持页面与图片去重。

**架构：** 输入法在微信宿主确认“发送/回车动作成功”后，通过进程内桥向辅助服务发出一次延迟截图请求。辅助服务以微信前台聊天页的无障碍内容变化为主触发，并补充明确的发送点击和非输入框文本变化；通知只保留为隐藏通知打开后的辅助信号。截图由已注册的微信聊天页适配器裁剪，并在持久化前用资源 SHA-256 去重。QQ、抖音在取得能排除直播/评论页的真实节点夹具前不启用通用前台适配器。

**技术栈：** Kotlin、Android AccessibilityService、Robolectric/JUnit、Room 持久队列。

---

### 任务 1：定义与验证前台聊天截图桥

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/ForegroundChatCaptureBridge.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/service/capture/ForegroundChatCaptureBridgeTest.kt`

1. 先写测试，覆盖微信/QQ/抖音允许、非聊天应用拒绝、连接后请求准确交付。
2. 运行定向测试并确认因实现缺失失败。
3. 实现最小桥和支持包名判断。
4. 重跑定向测试确认通过。

### 任务 2：发送动作与聊天页事件接入

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/ImeService.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveChatAccessibilityService.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/xml/passive_chat_accessibility_service.xml`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/service/ImeServiceKeyEventTest.kt`

1. 先写失败测试：宿主发送动作成功才请求聊天截图；普通应用和失败动作不请求。
2. 输入法发送边界接入桥，不在普通文字 commit 时提前截图。
3. 辅助服务接收请求，延迟读取当前前台页；包名不匹配或已离开聊天应用时丢弃。
4. 增加点击、文本变化事件，使宿主“发送”按钮和已打开聊天页新回复不依赖通知。

### 任务 3：去重与完整验证

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/CaptureCoordinatorTest.kt`
- 修改：`docs/android-integration.md`

1. 增加截图资源哈希相同不重复、哈希变化才新增的回归测试。
2. 更新集成文档，明确前台页面事件/发送动作为主，通知为辅助。
3. 运行 Android 定向测试、相关采集测试和 Debug APK 构建。
4. 真机验收：自己发送、对方在当前聊天页回复、重复静止画面、直播间/非聊天页四组场景；本轮无设备时明确标记未验收，不以单测代替。
