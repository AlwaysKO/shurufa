# 微信输入法 GIF 静态化：接收端分流证据

## 结论与边界

问题未解决。搜狗的 URI 提交调用可以复现，但当前手机微信 8.0.77（versionCode 3160）的输入法图片入口另有默认输入法标识检查：文件是 GIF，且 Settings.Secure.default_input_method 字符串包含搜狗或 QQ 拼音标识，才启用表情分支。我们的正常输入法标识不满足，走普通图片分支。不能再以改扩展名、MIME、commitText 返回 true 或单元测试通过宣称动态发送修复。

本轮仅调查，没有继续改生产发送代码，没有自动发送微信消息、修改默认输入法或安装软件；也没有读取聊天数据库。限制已定位到这一入口，不等于证明微信所有可能的集成入口均无解。

## 手机上的实际交接文件

- 安装版：20260908.10，lastUpdateTime 2026-09-08 10:50:00。
- 安装 APK SHA256：b4952c0e69f06d4c8424043695cb1e7c484b08dbd9eb66b4b9c6e602b0706848。与前轮打包 SHA 不同，不能用版本号认定是完全相同二进制。
- 应用私有 cache/expression/wechat 内实际存在 10:52 的 .0 交接副本，证明手机运行过此路径。
- 副本 187931 字节，SHA256 c5a931212a3207645d50dedc047bb3dedf4453f53ce3d8abe20274ffe2adf829，GIF，240×240，16 帧，loop=0，与“谢谢”原素材相同。
- 只读 ADB 检查时默认输入法是搜狗。没有反推用户此前发送时的设置；我们包名和该白名单的差异独立存在。
- logcat 指定 ExpressionSendDiag 标签未返回有效诊断事件；不据此编造 commit 结果。

## 搜狗调用链（20.14.0 APK）

反编译根目录 /tmp/sogou-full-20260831/sources。

1. com/sogou/expressionplugin/expression/manager/c.java，l：微信 GIF 选择 gifLocalPath，调用路由 eo（正常聊天分支）。该大方法 JADX 重构存在异常条件，未仅凭它推断全部行为。
2. com/sohu/inputmethod/routerimpl/n.java:891，eo → emoji.d.e。
3. com/sogou/bu/input/inputconnection/emoji/d.java：e 依次检查兼容入口，微信最后走 d；Android 29+ 私有来源异步复制，emoji.b 回调 h。
4. com/sogou/imskit/core/input/inputconnection/emoji/a.java:63，c 纯字节复制到 .0，不解码 GIF。
5. emoji/d.java:273–281：FileProvider URI、grantUriPermission 微信、CommitWeixinTrickManager.g、输入连接 commitText URI、QuickAccessibilityService.b 标记发送状态。
6. com/sogou/bu/input/inputconnection/c.java:486：j 最终调用 inputConnection.commitText(charSequence,1)。
7. CommitWeixinTrickManager 是延迟检查 URI/路径是否残留的兼容回退（路径、分享），不是把静态图恢复成 GIF。QuickAccessibilityService.b 是异步标记状态；手机当前没有启用无障碍服务。

## 微信接收端（从手机只读提取安装 APK）

APK 保存在 /tmp/wechat-current-20260908.apk，定向反编译，不将第三方完整源码纳入仓库。

- /tmp/wechat-cm.java:1079–1125，com.tencent.mm.ui.chatting.component.cm.n0（原符号 SendImgComponent.sendImgFromInput）：读取 default_input_method，将 GIF 文件头判断和两个输入法标识匹配合并为布尔值，传入发送确认回调 gm。
- /tmp/wechat-cm-simple.java:399–444：另用 JADX simple 模式核查分支跳转，确认上述条件不是 Java 重构造成的反转。
- /tmp/wechat-y1.java:105–122，y1.d：读前 6 字节并检测 GIF 头；不检查文件扩展名。因此 .gif/.0 变化不能改变输入法标识条件。
- /tmp/wechat-gm.java，gm.a：表情分支在聊天表情回调可用时构造 WXEmojiObject；非表情分支走普通图片发送任务（component_copy_send_img）。
- /tmp/wechat-wp5-i.java，wp5.i.a：私有命令 com.sogou.inputmethod.exp.commit，Bundle EXP_PATH_URI，也交给同一个 cm.n0。因此仅换成这条私有命令不能消除相同限制。

## 后续约束

保持用户验收条件：不重新选择聊天，收到的是可播放 GIF。未经实测的入口不能标作已修复；不能把普通图片或分享选择页当成等价实现。本轮不追加第四个猜测补丁，不修改/冒用搜狗身份，不改微信。若继续开发，需要先确定并验证不受该入口限制的合法兼容方案，再讨论发送架构；当前没有已验证可用的替代直发方案。

## 继续调查：其他入口与组件标识实验边界

本轮仍没有修改生产代码或操作手机发送。

### 标准内容提交

扫描当前 APK 各 DEX 的实际 commitContent 实现，发现普通代理 dq5.v 直接透传，g2.y 返回 false，另有 Flutter 和 Nirvana 的输入通道。后两者是引擎内容回调，不能证明当前微信聊天框会采用它们。MMEditText 的代理与 MMCustomEditText 的 vp5.k 均未新增 GIF 发送处理；当前未找到可接入的聊天 GIF 标准内容分支。Android 官方图片键盘协议只负责把内容交给宿主处理，不保证宿主按动图发送： https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard 。

### 剪贴板

已追踪不同于 n0 的富内容粘贴入口：
- ChatFooter$$e.a 读取 ClipData，a85.b.a 按真实 Provider MIME 分类：image/* 一律 type=2；其余可落文件 type=4。
- jk.l0 展示当前会话确认，gk.a 对 type=2 调 cm.m0。
- cm.m0 使用普通图片发送任务（component_send_img），没有转入 WXEmojiObject；type=4 是附件发送，也不等价于气泡内播放的 GIF 表情。
- URI 以文本粘贴时，ld.onTextChanged → cm.n0，回到前述默认输入法检查。
因此不将剪贴板方案标为已解决，也未覆盖用户剪贴板做盲测。

### 开放 SDK

当前 APK 的 SendMessageToWX.Req 中 scene=3 是指定联系人，检查 userOpenId/openId；不是自动获取当前聊天对象。WXEntryActivity 将 scene=0 和 scene=3 映射为不同分享业务类型，后者带 userOpenid 并由处理结果提供目标用户名。SendAppMessageWrapperUI 使用 Select_Conv_User 等目标信息。未发现供普通输入法取得当前聊天 OpenID 的接口；不能因为名字含 Session 就把它当成当前会话直发。

微信官方 Android 分享文档本次读取失败，因此以上具体结论来自本机 APK，不引用第三方博客充当官方保证。没有尝试直接调用内部 Activity 或伪造 SDK 授权字段。

### 一个需明确同意后才做的兼容性实验

进一步精确描述 n0 的检查：它对 Settings.Secure.default_input_method 的完整组件字符串做 contains，而不是在此处分支验证应用签名。组件字符串同时包含应用包名和 Service 类名。因此从代码条件推导：保留我们自己的 applicationId，仅让测试用 Service 组件名包含其匹配串，也可能改变该布尔分支。

这不是已发现的官方新入口，而是对硬编码兼容名单的规避；尚未安装或实机验证，不能宣称 GIF 会动。若用户明确同意，只考虑独立调试入口、保留旧 Service 和应用数据、可切回，不变更搜狗应用，不伪造其签名，不自动发送。需要重新选择一次测试输入法组件（不是每次选择聊天）。微信未来更新、其他后续检查及应用分发约束仍可能使方案不适用。未获同意前不实现。
