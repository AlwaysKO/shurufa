# 标题栏裁切修复与单行识别评估

> 使用 superpowers:executing-plans / test-driven-development / verification-before-completion；用户已确认继续修复并评估，当前分支开发，不自动替换模型。

## 范围
- 正式链路保留 ML Kit，仅修正微信标题输入和状态栏错选；不改姓名、繁简转换、会话合并及正文采集。
- 本机真机证据：1200px 宽、density 3.25、系统 statusBars 底部 126px，微信标题栏至约 270px；旧 216px 裁切丢字底，ML Kit 返回框超出输入后被丢弃，状态栏反而被选择。
- 使用系统状态栏尺寸、截图窗口原点和微信约 44dp 标题带（App 布局适配值）生成独立标题栏小图，不再用屏宽推算标题底边。窗口已排除状态栏时不得重复减去顶部；旧文件回退也使用同一请求几何。标题带只到工具栏，不扩大到正文；更多主题、分屏、字体与宿主版本需真机回归。
- 增加状态栏位置+时钟前缀噪声保护，不通过放宽越界框接纳截断文字。
- tiny rec-only 只在已获授权的离线诊断应用评估：只加载识别模型，不加载检测模型或 OpenCV，使用 RGB/NCHW 48 高度预处理及官方 CTC 字典。定位在已知标题带中做像素边界收紧；触边/空白等不安全输入拒识，不根据正确答案纠错。

## 任务
1. 在 TitleOcrInputTest / WechatScreenshotIdentityTest 添加失败测试：状态栏误抢、按 density/inset 裁切、窗口原点、原始像素/正文隔离、异常输入生命周期。
2. 修改 TitleOcrInput.kt、WechatScreenshotIdentity.kt、PassiveChatAccessibilityService.kt；首次及确认截图走同一几何，不修改既有分类修复。
3. 运行全部 capture/service.capture 回归；同时在临时 probe 添加新的像素定位与单行识别测试，使用同一标题带对比 ML Kit 与 tiny_rec。
4. 诊断包无联网权限、原签名检查后更新独立包并实测；记录原始输出、标题、PSS采样及延时，不声称两例代表整体准确率。
5. 独立审查、主应用打包、固定目录交付和证书/哈希核对。诊断安装已有授权；正式 APK 安装另行确认，不擅自覆盖当前正式输入法。

## 验收边界
- tiny_rec 若未达标不加入正式包；“只识别标题更省内存”仍是待验证假设。
- 44dp 是本轮微信布局适配值，不是 Android 所有 App 通用约束；状态栏系统资源不可读取、特殊窗口或字体导致边界变化需报告，不宣称全机型通过。
