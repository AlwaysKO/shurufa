# 连续中文推荐与安静发送实现计划

目标：微信先输入“然后”再补“呢”仍自动推荐“然后呢”；选图不闪过程文字/微信正常交接 Toast。保留失败反馈、重复点击拦截、GIF 原字节发送及 QQ/抖音仅手动推荐。

用户于 2026-09-18 确认同一输入框、不移光标、不收键盘；并确认上轮 GIF 已能发送。该确认不扩展为三 App 全部内容验收。

## 调查与验证
- 已有 ExpressionManualSearch 能续接中文，但 InputView 的选区识别只认可正向长度变化；拼音被候选汉字替换时光标可能向前收缩，误判成用户移动，清掉上下文和刚提交的查询。
- 通过 ImeService 的真实选区入口重现带组合范围的“然后 + ne → 呢”，先运行失败回归，再最小调整选区判断；非自身提交/用户移动仍清理。
- 修改 ExpressionPanel 的准备遮罩为无文字透明拦截层（保留防重复触发），WechatSubmitted 不 Toast。失败 Toast 不变。
- 测试文件：android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/ExpressionManualSearchInputViewTest.kt；扩展连续输入/重复选区/手动移动和发送提示断言。
- 生产文件：同模块 keyboard/InputView.kt、service/ImeService.kt（只转交组合范围）、expression/ui/ExpressionPanel.kt、res/layout/sdk_expression_panel.xml。
- 定向回归与原 GIF/AI 合成/手动推荐测试通过后 assembleOfflineDebug，核对签名与 SHA，交付 APK，不自动安装、不发送消息。
- 保留工作区其他会话未提交改动，尤其聊天识别与采集。不提交混合工作区。

## 回归进展
- 首轮：无移动选区回调吞掉待确认提交已失败复现（期望“然后呢”，实际“呢”）；过程文字断言也按预期失败。
- 完整 ImeService → InputView.onUpdateSelection 测试触发本机 JVM 不具备的 Rime JNI；不是产品缺陷证据。改测真实 InputView 的推荐选区处理入口，仍传入真实组合范围，避免伪造 JNI。
- 为精确识别组合替换，先做无行为变化的范围透传：ImeService 将 candidatesStart 一并传到 InputView（原来只传末尾）；随后单独重跑光标回缩失败测试，才修改判定逻辑。
- 静默 UI 保留透明点击拦截层与 isPreparing，避免为了删提示误删重复发送保护。
- 单独光标回缩测试已正确失败：期望“然后呢”，实际 query=null；不是 JNI 错误。最小实现后第一轮 148 项定向测试通过（quiet-send-green.log/results.json）。
- 只读审查指出组合提交与无移动负范围通知交错时，保留 pending 仍不足，旧组合范围也不能提前丢失。新增该序列的先红回归，并保护跨编辑器范围不能复用；完成后再做最终回归/打包。
- 扩展回归 200 项中 199 项通过；唯一失败是旧 ExpressionPanelTest 明确要求显示“正在交付”文字，与本次用户已确认的静默要求相反。按新契约更新该测试为无文字、透明但仍拦截点击，不改变生产代码来迁就旧契约；随后重新完整运行。

## 最终交付
- 完整回归 200 项通过，0 失败/错误/跳过；assembleOfflineDebug 成功。日志 quiet-send-final-verified.log，结果 quiet-send-final-results.json。
- 审查所指出的交错回调已先红后绿，最终复核无重要问题。保留真实失败提示、未确认微信交接不清文字/卡片、防重复发送及 QQ/抖音 manual-only。
- APK：`artifacts/apk/shurufa-continuous-quiet-20260918.14-2f080760.apk`；versionCode `2026091814`；SHA256 `2f080760e1eea2c86e9904d8e9eb7542f9c03d5d2fee17bbbeb48a14b83bbcf3`。签名 v1/v2 验证通过，APK 被 Git 忽略。
- 首次签名检查未加载 Java 环境，工具未执行；加载既有 Android env 后已重新验证通过。既有 META-INF 签名覆盖警告仍在。
- 没有安装、没有代发消息、没有提交混合工作区；本轮真机交互效果待用户验证。发送器、GIF/AI 合成与采集/OCR 逻辑没有为本轮需求修改。
