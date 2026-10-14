# 微信 GIF 重复元数据兼容修复实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 修复“抽烟”第一张 GIF 在微信中无确认框的问题，保留原图、动画及既有发送协议。

**架构：** 仅在发送副本中去除首帧前完全重复的 XMP 应用扩展，保留第一份及不同内容的 XMP。帧数据、控制扩展、循环及其他扩展逐字节不变；无重复的素材不改字节。复用现有交付缓存的哈希、容量、原子写入、读取保护与 URI 授权。

**技术栈：** Kotlin、Java 流、Robolectric、Android BitmapFactory、Gradle。

## 已确认依据

- 用户确认微信点击“抽完这根烟……”GIF无反应，第二张PNG和其他GIF能发送。
- 当前手机：ELI-AN00、Android API 36；微信8.0.78 / 3180；输入法20261013.00 / 2026101300，既有兼容IME组件。
- 失败GIF SHA256：`5c70cd8398dd4c99c71d34a72aa9b2804eb70155b267e1db6ac5a882432edd6b`；250×223、32帧、125367字节。
- 首帧前有32份完全相同的1044字节XMP块，首个图像描述符位于33640。保留一份后93003字节，32帧RGBA、逐帧时长、循环设置完全相同。
- 用户明确批准临时解码对照：真机 `decodeByteArray` 原图成功；`decodeStream` 完整像素解码原图null，去重副本成功。bounds-only两者均有正确尺寸，不能仅用尺寸判定能发送。
- 本地Robolectric原生图形环境中数组/流均成功，不能替代该真机复现。微信已获得原GIF临时读取权限；同版本接收端检查确认框图片采用流式解码。
- 原始诊断仅在忽略目录 `.runtime/smoking-send/` 与 `artifacts/diagnostics/smoking-send/`；真机 `/data/local/tmp/shurufa-smoking-decode-20261014` 已删除并核对。未发送消息、未改图库或手机原件。

## 任务1：失败回归

修改 `android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/send/WechatGifCommandTest.kt`：合成带重复XMP的既有GIF，断言真实FileProvider交付只有一份XMP，原文件不变；再次发送复用相同URI。先运行该新增用例确认旧sender断言失败，不依赖私有原图。

## 任务2：最小实现与边界测试

新增同主目录 `GifDeliveryMetadata.kt`，仅扫描有上限的首帧前缀。用新前缀与原文件剩余流组合，不整图解码/重编码，不覆盖源文件。缺失、截断或超预算前缀不猜测修复。

修改 `ExpressionContentSender.kt` 的 `prepareDeliveryFile`：有去重计划时不走已有后缀捷径，以去重流计算交付哈希并写入既有容量受控缓存；未变化走旧链路。

新增同测试目录 `GifDeliveryMetadataTest.kt`：正常GIF不改、不同XMP及未知扩展保留、帧/循环字节一致、截断和扫描上限、幂等。

## 任务3：验证与交付

在项目根执行（先 `source /home/ko/android-tools/env.sh`）：

```sh
./android/YuyanIme/gradlew -p android/YuyanIme :yuyansdk:testOfflineDebugUnitTest --tests '*GifDeliveryMetadataTest' --tests '*WechatGifCommandTest' --tests '*ExpressionContentSenderTest' --tests '*ExpressionFlowTest' --tests '*ExpressionSendControllerTest' --offline --console=plain
./android/YuyanIme/gradlew -p android/YuyanIme :app:assembleOfflineDebug --offline --console=plain
git diff --check
```

预期定向测试通过。核对本次生成APK的版本、原签名、非testOnly以及E盘副本SHA256，成功后仅删除对应WSL APK副本。未获授权不安装、提交、推送或发布后台；微信最终确认框/发送由用户验收，QQ/抖音、微信/抖音采集未做真机回归则明确说明。

## 执行结果

- 失败回归已确认：旧实现交付221179字节，期望188970字节（完全重复XMP应只保留一份）；1项断言失败，不是编译或环境错误。
- 新增有界前缀扫描与组合流，图库原件不写入；无重复、非GIF、首帧后的元数据不改。仅发送副本使用去重流和内容哈希。
- 生产Kotlin类对真实失败GIF的输出与已通过真机流式解码的试验副本逐字节相同；SHA256 `646c0358a67bb96aa8176b84b7e3f66f062c0675a49e167b281ca4c28c030389`，93003字节，且再次检查无重复改写。
- 自动化发送回归、代码审查与APK交付进行中。

- 用户随后明确批准：修复包验证后覆盖安装当前连接手机，由用户自行验证；不得卸载、清数据或自动点击发送。该单次授权不扩展为以后自动安装。

- 首轮联合回归58项中57通过；缺失文件的旧诊断隔离用例发现新增扫描抢先失败。已限制仅真实文件进入新增元数据检查，保留原用例，不改断言掩盖回归。只读独立审查及该补丁复核无阻断问题；最终重跑中。


### 最终验证与交付（2026-10-14）

- 58项定向测试全部通过，0失败/错误/跳过：WechatGifCommand18、GifDeliveryMetadata7、ExpressionContentSender13、ExpressionFlow15、ExpressionSendController5。初始失败用例及真实文件诊断保留在忽略目录。
- 独立只读审查及缺失文件守卫复核无阻断问题；`git diff --check` 通过。
- APK构建成功：`20261014.11 / 2026101411`，debug包名 `com.yuyan.pinyin.offline.debug`；非testOnly；原签名在API23/27/28/32/36核验通过。
- 唯一推荐文件：`E:\Projects\shurufa-android\apk\shurufa-2026-10-14-v20261014.11-2026101411-debug-738a8d8a.apk`。
- APK SHA256：`738a8d8a49a218e0c395dac3b8f110a076be9d0b5cc011f9700d435667a1dfd6`；源/E盘一致，成功后仅删除对应WSL构建APK副本。
- 按本次用户明确授权，Windows ADB `install -r` 返回Success；读回版本及已安装base.apk SHA与本次交付文件一致。原输入法组件、本应用无障碍启用项、已授予权限保持；失败GIF原缓存SHA仍与原图一致。未卸载、未清数据、未代发消息。
- 已请用户在新版点击原“抽烟”GIF验收确认框/动画；截至本段写入尚待用户结果。真机解码对照不是最终聊天发送验收。未执行微信/抖音采集端到端回归、QQ/抖音发送真机回归，未宣称这些已通过。
- 无Git提交/推送，无后台改动/部署，无图库原图替换或清理。


### 用户真机验收

- 2026-10-14 用户在覆盖安装新版后明确反馈：**“已发送成功，动画正常”**，对应微信“抽烟”第一张GIF。
- 同时只读检查手机交付缓存已出现本次去重产物 `646c0358a67bb96aa8176b84b7e3f66f062c0675a49e167b281ca4c28c030389.gif`（93003字节），原125367字节GIF仍保留。本条更新前述“待用户验收”状态，仅确认本次设备/微信版本/该图，不扩大为所有素材、App或采集验收。
