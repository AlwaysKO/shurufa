# 输入法按住说话与自方语音文字采集实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 修复输入法长按空格语音无效，实现按住识别、松手上屏，并把本机在微信中的语音转写文字同时写入行为事件和聊天采集。

**架构：** 由专用透明 Activity 申请 `RECORD_AUDIO`；`ImeService` 优先创建 Android 设备端识别器，不可用时使用系统识别器。长按触摸链路区分开始、松手和取消；部分结果作为 composing text，最终结果一次上屏。微信中的结果以 `outgoing/text` 写入截图兜底的未识别会话，不伪造联系人身份。

**技术栈：** Kotlin、InputMethodService、SpeechRecognizer、Room 采集队列、Robolectric/JUnit。

---

### 任务 1：修复麦克风权限

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/ui/activity/VoicePermissionActivity.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/AndroidManifest.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/values/themes.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/values/strings.xml`

1. 先测试权限不足时生成 Activity 请求决策。
2. 删除无效的 `Context.requestPermissions` 反射路径。
3. 由透明 Activity 申请录音权限，用户再次长按即可识别。

### 任务 2：按住说话与端侧优先

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/ImeService.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/BaseKeyboardView.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/service/VoiceInputPolicyTest.kt`

1. 先测试 API/SDK 能力到端侧或系统模式的选择。
2. 长按超时后启动，松手 `stopListening`，取消则 `cancel`。
3. 开启 partial results，最终文字只上屏一次。
4. 设备端识别不可用或语言不支持时仅回退一次到系统识别。

### 任务 3：自方语音文字上报

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/OutgoingVoiceCapture.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/OutgoingVoiceCaptureTest.kt`
- 修改：`docs/android-integration.md`

1. 先测试只有微信中的安全非空转写生成 `outgoing/text`。
2. 保留现有 `voice` 行为事件，另写入聊天采集队列。
3. 标注 `capture_source=ime_voice_input`、`input_mode=voice`和“已转写但无法证明已点击发送”，避免伪造发送状态。

### 任务 4：验证与真机安装

1. 运行语音、长按、采集及截图兜底测试。
2. 用已安装 APK 相同签名构建 `offlineDebug`。
3. ADB 恢复后保留数据安装，验收首次权限、按住/松手、文字上屏与后台记录。
