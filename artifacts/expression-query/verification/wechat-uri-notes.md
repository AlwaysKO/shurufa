# 微信URI误入正文修复

用户观察：发送后聊天输入框出现content://本输入法FileProvider/.../wechat/handoff-*.0。

根因已在代码确认：ExpressionContentSender的微信GIF分支复制为.0并用commitText提交URI。commitText接受只是正文插入，不表示图片被接收。

本次删除该分支。微信与其他宿主统一使用标准MIME协商和commitContent，保留原GIF字节与读授权；未声明支持或拒收时不伪成功。InputView原有fallback保存原图至相册并明确提示，未新增自动分享或消息发送。未清除设备上已经插入的URI文字或历史handoff文件。

TDD：针对微信的4项行为断言先失败（12项测试中4失败），最小删除专用分支后sender12+flow12+controller5共29项通过。独立规格与质量审查随后进行，最终全Expression测试和APK构建另见同名前缀日志。未进行本次微信真机动态直发验证，不将阻止URI误入正文描述为已经解决所有微信GIF直发兼容性。

素材：本次未读取/导出/上传搜狗缓存。用户先前限定自有接口仅接入ai-original/cc0/public-domain/licensed；来源或授权不明的缓存不批量接入。该FileProvider URI说明本输入法提供缓存文件，不证明原始素材出自搜狗。
