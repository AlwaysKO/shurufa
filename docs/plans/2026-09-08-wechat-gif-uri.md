# 微信 GIF 当前聊天 URI 交接实现计划

> **For Claude：** 使用 superpowers:executing-plans、test-driven-development、verification-before-completion。

**目标：** 不弹分享选择页，按搜狗微信分支观察到的交互协议交付原 GIF，再由用户在文件传输助手实机验收动态效果。

**架构：** 仅微信 GIF：给 com.tencent.mm 临时读 URI 权限，通过当前 InputConnection.commitText(uri, 1) 交接。其他宿主或静态图保留 commitContent。不复制搜狗实现，不假冒其包名或 AppID。返回独立 WechatSubmitted 状态，不能以 commitText=true 声称媒体发送完成。不新增自动按发送、循环重试或失败后盲删聊天文字的行为；失败保留现有原文件相册 fallback，绝不自动打开分享页。

**技术栈：** Kotlin、Android InputConnection/FileProvider、Robolectric。

## 证据
本机 /tmp/sogou-full-20260831/sources：expressionplugin/expression/manager/c.java 微信 GIF 分支调用 routerimpl/n.eo → bu/input/inputconnection/emoji/d.e/d/h；d.h 授权原文件 URI 并调用 bu/input/inputconnection/c.j，后者调用 commitText。CommitWeixinTrickManager 另行检测未被消费的 URI 并降级分享。用户已在同机同微信验证搜狗一步发送且会动。静态分析不能证明 shurufa 的 URI 一定被微信识别。

## 步骤
1. Sender 测试先改为微信 GIF 授权原 URI、commitText、无 Intent、无 commitContent；失败不降级分享。运行确认红。
2. 新增/替换交接结果 WechatSubmitted，接通 Controller/Flow/UI 的清理与提示，测试语义一同更正，不称发送成功。
3. 实现最小微信 GIF 特例，运行表情相关定向测试和 Windows assemble/install；不改候选/采集。
4. 请用户在文件传输助手验证原 GIF 的动态效果，若 URI 留在文本框则记录失败，不冒充成功、不自动清理不确定的用户文字。
5. 标点独立小改 42f→50f 已先红后绿（6 tests），Studio 日志确认已打开 SogouKeyboardTypography.kt，之后用户可自行调整，后续不覆盖该文件。

## 验证记录

- 新交接结果的测试先因缺失 WechatSubmitted 失败；实现后 Linux 4 类/75 tests 全通过（0 failures/errors/skipped，4m34s），测试 KSP 正常开启。
- Windows 定向测试最初 75 项中 7 项失败：6 项为 AndroidX FileProvider 对 Windows 测试临时路径无法匹配配置根，另 1 项为 Robolectric SQLite connection pointer；未为宿主测试环境修改 Android 文件权限/路径实现。相同测试在 Linux 全绿。
- 只读审查未发现本轮确定重要缺陷。边界：InputView 原有发送入口会清空宿主输入，这是此前行为；本轮只保证不新增失败后的 URI 盲删或自动重发，不宣称整个旧入口完全不清文字。
- URI 使用 FileProvider 的具体文件只读授权，不开放整个目录，不假冒搜狗身份。接口返回接受不代表微信已完成 GIF 发送。
- 尚待本应用真机确认“不换聊天且保持动画”；用户已确认的是同机搜狗对照，而不是本测试包。

## 2026-09-08 补充：实机仍静态后的差异验证

用户确认 URI 原 GIF 路由仍静态，前轮不算解决。搜狗反编译 `bu/input/inputconnection/emoji/d.java:200` 在 Android 29+ 私有路径走搬运，`bu/basic/ui/viewpager/b.java:108` 指定 `.0`，`imskit/core/input/inputconnection/emoji/a.java:63–84` 纯字节复制。其标准 FileProvider 按真实后缀返回 application/octet-stream；当前直接交付 .gif 是 image/gif。手机 Android 36；只读检查缓存“谢谢”素材有 16 帧、15 个不同画面，不能证明用户最后一次必定点选该素材。

最小对照实现：仅微信 GIF 在 IO 上复制到 expression/wechat 下独立 .0 交接文件，保留原 GIF 和其他渠道。IO 后复核 EditorInfo 未变才获取连接、授权并 commitText；日志核验实际交接副本的字节和 MIME。成功仍只表示 WechatSubmitted，不宣称动图发送成功；不得自动代用户发送或清宿主文字。

验证：真实 FileProvider 回归断言 .0 / octet-stream / 源与 URI 字节一致，目标变化时拒绝交接；定向测试后覆盖安装并由用户同素材复测。这是基于证据的差异实验，不能预判 MIME 是唯一根因。

审查确认两处调用方均返回 service.currentInputEditorInfo，同一会话不是每次新建对象，identity 复核与实际接线兼容。交接副本保留于 cache 供微信异步读取，不在 commitText 后立即删除；重复发送缓存的过期清理属于后续生命周期事项，当前尚未增加定时清理。
