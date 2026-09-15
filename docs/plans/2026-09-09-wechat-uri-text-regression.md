# 微信GIF路径误入正文修复计划

> 使用superpowers子代理实现、规格审查、独立质量审查；在main原目录，保留用户提交及client无关修改。所有新增修改及产物ko:ko。

## 根因与最小设计

用户反馈发送后输入框出现content://.../wechat/handoff-*.0。ExpressionContentSender.send的微信GIF专用分支复制原件为.0并调用commitText(uri)，这仅提交文字，不能保证微信解析成图片。移除这一未经可靠能力协商的专用路径，不再向文本框写URI，也不伪装MIME或模仿其他输入法私有协议。

微信与其他应用一样，只有支持声明的image/gif时才用标准commitContent；不支持则返回UnsupportedTarget，走既有fallback：InputView保存原图至相册并提示，发送确认框显示失败及保存选项。保留GIF原字节，不能静态化；不新增自动点击/自动发消息。沿用既有fallback及失败/已保存结果语义，避免无关重构；遗留WechatSubmitted类型若无需变更可暂保留但生产sender不再返回。

## 实现与验证

1. 修改ExpressionContentSenderTest先RED：微信GIF不调用commitText、不创建handoff，支持GIF走commitContent且MIME/字节正确，不支持时UnsupportedTarget，失败不能标记Sent。
2. 最小删除ExpressionContentSender中不可靠分支，必要时调整现有断言；不能删质量断言来获得通过。
3. 运行sender/flow/controller/dialog相关回归，保留RED/GREEN证据；规格审查→独立质量审查。
4. 最终全部Expression相关测试与assembleOfflineDebug，按既有测试临时1536m单fork配置，不改项目构建。记录归属与APK哈希，无设备则不宣称微信实际发送成功。

## 素材范围

本次不读取/导出搜狗缓存，不批量上传来源许可不明素材。后续只有明确授权、CC0、公版或原创素材可入自有接口；本地缓存URI不能证明原始来源。
