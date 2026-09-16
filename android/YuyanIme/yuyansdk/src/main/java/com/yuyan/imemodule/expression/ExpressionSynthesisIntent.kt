package com.yuyan.imemodule.expression

/** 保守本地表达规则，不是通用幽默理解；手动选图不经过此门禁。与服务端同步。 */
object ExpressionSynthesisIntent {
    private val refusal = Regex("(不要|别|禁止|停止|不能|不该).{0,6}(嘲讽|调侃|开玩笑|阴阳怪气)")
    private val serious = Regex("(不是|没有|没在|并非).{0,3}(开玩笑|调侃|嘲讽)|我是认真的")
    private val markers = listOf("笑死", "笑不活", "绷不住", "蚌埠住", "离大谱", "原地裂开", "你可真是个人才",
        "你是真会", "给你颁个奖", "给爷整笑", "戏精", "显眼包", "小丑竟是我",
        "我可真谢谢你", "阴阳怪气", "调侃", "嘲讽", "开玩笑", "逗你玩")

    fun matches(query: String): Boolean {
        val text = ExpressionQueryMatching.normalize(query)
        return !refusal.containsMatchIn(text) && !serious.containsMatchIn(text) && markers.any(text::contains)
    }
}
