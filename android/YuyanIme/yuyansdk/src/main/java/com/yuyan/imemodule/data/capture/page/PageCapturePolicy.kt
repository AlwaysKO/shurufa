package com.yuyan.imemodule.data.capture.page

import com.yuyan.imemodule.data.capture.media.ConversationTitleSimplifier
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot

internal enum class PageKind { CHAT, CONVERSATION_LIST, PAYMENT, MEDIA_FEED, IMAGE_POST, MINI_APP }
internal data class PageLabel(val text: String, val bounds: IntRect, val editable: Boolean = false, val password: Boolean = false)
internal data class PageDecision(val kind: PageKind?, val reason: String, val contentKey: String? = null)

/** 页面类别与联系人身份完全分开；正文关键词不充当导航，缺证据不默认放行。 */
internal object PageCapturePolicy {
    private val packages = setOf("com.tencent.mm", "com.ss.android.ugc.aweme")
    private val inputPrompt = Regex("(?:请)?(?:再次)?(?:输入|填写)(?:您的|你的)?(?:[0-9一二三四五六七八九十]{1,2}位)?(?:支付|短信|动态|登录|登陆|交易|身份)?(?:密码|验证码|验证代码)")
    private fun normalized(label: PageLabel): String =
        ConversationTitleSimplifier.simplify(label.text.take(200)).filterNot(Char::isWhitespace)

    // ML Kit 可把同一横排的独立导航合成一行；只拆真实空白，不猜无分隔文本。
    private fun navigation(band: List<PageLabel>) = band.flatMap { label ->
        listOf(normalized(label)) + label.text.trim().split(Regex("\\s+"))
            .map { normalized(label.copy(text = it)) }
    }.toSet()

    /** 仅固定布尔/数量，用于定位分类失败；不返回OCR原文，不改变分类证据。 */
    fun feedNavigationEvidence(bounds: IntRect, labels: List<PageLabel>): FeedNavigationEvidence {
        val height = bounds.bottom - bounds.top
        fun band(from: Double, to: Double) = navigation(labels.filter {
            it.bounds.left >= bounds.left && it.bounds.right <= bounds.right &&
                it.bounds.right > it.bounds.left && it.bounds.bottom > it.bounds.top &&
                it.bounds.top >= bounds.top + height * from && it.bounds.bottom <= bounds.top + height * to
        })
        val top = band(0.0, .22)
        val bottom = band(.86, 1.0)
        return FeedNavigationEvidence(labels.size, "关注" in top, "推荐" in top, "朋友" in top, "看剧" in top,
            "首页" in bottom, "消息" in bottom, "我" in bottom, "+关注" in bottom || "关注" in bottom,
            top.any { it.contains("关注") }, top.any { it.contains("推荐") })
    }

    private fun sensitive(labels: List<PageLabel>, bounds: IntRect): Boolean {
        if (labels.any { it.password }) return true
        val pieces = labels.map { it to normalized(it) }
        if (pieces.any { inputPrompt.containsMatchIn(it.second) }) return true
        // OCR 可将同一输入提示分成相邻两行；合并只用于拒绝，绝不拼正向导航证据。
        val sorted = pieces.sortedWith(compareBy({ it.first.bounds.top }, { it.first.bounds.left }))
        for (i in 0 until sorted.lastIndex) {
            val (first, a) = sorted[i]
            val (second, b) = sorted[i + 1]
            val gapY = second.bounds.top - first.bounds.bottom
            val gapX = kotlin.math.abs(second.bounds.left - first.bounds.left)
            if (gapY <= (bounds.bottom - bounds.top) * .06 && gapY >= -maxOf(first.bounds.bottom - first.bounds.top, second.bounds.bottom - second.bounds.top) &&
                gapX <= (bounds.right - bounds.left) * .35 && inputPrompt.containsMatchIn(a + b)) return true
        }
        return pieces.any { it.second in setOf("支付密码", "交易密码", "验证码", "短信验证码") } &&
            pieces.map { it.second }.filter { it.matches(Regex("[0-9]")) }.distinct().size >= 6
    }

    fun classify(
        packageName: String,
        bounds: IntRect,
        labels: List<PageLabel>,
        secureWindow: Boolean = false,
        chatVerified: Boolean = false,
        miniAppChromeVerified: Boolean = false,
    ): PageDecision {
        fun reject(reason: String) = PageDecision(null, reason)
        if (packageName !in packages) return reject("unsupported_package")
        if (secureWindow) return reject("secure_window")
        if (sensitive(labels, bounds)) return reject("sensitive_input")
        if (labels.any { it.editable } && !chatVerified) return reject("editable_non_chat")
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width <= 0 || height <= 0) return reject("invalid_bounds")
        if (chatVerified) return PageDecision(PageKind.CHAT, "verified_chat_adapter")
        val visible = labels.filter {
            it.bounds.left >= bounds.left && it.bounds.right <= bounds.right &&
                it.bounds.top >= bounds.top && it.bounds.bottom <= bounds.bottom &&
                it.bounds.right > it.bounds.left && it.bounds.bottom > it.bounds.top
        }
        fun inBand(from: Double, to: Double) = visible.filter {
            it.bounds.top >= bounds.top + height * from && it.bounds.bottom <= bounds.top + height * to
        }
        val top = navigation(inBand(0.0, .22))
        val bottom = navigation(inBand(.86, 1.0))
        val isWechat = packageName == "com.tencent.mm"
        if (isWechat && top.any { com.yuyan.imemodule.data.capture.media.canonicalWechatPageTitle(it) == "微信" } &&
            bottom.containsAll(setOf("微信", "通讯录", "发现", "我"))) {
            return PageDecision(PageKind.CONVERSATION_LIST, "wechat_navigation")
        }
        if (!isWechat && top.any { it == "消息" || it == "私信" } && bottom.containsAll(setOf("首页", "消息", "我"))) {
            return PageDecision(PageKind.CONVERSATION_LIST, "douyin_navigation")
        }
        val receiptHeader = inBand(0.0, .35).any { normalized(it) in setOf("支付成功", "收款成功", "转账成功", "交易详情") }
        val fields = inBand(.25, .95).map(::normalized).toSet()
        val amount = visible.any { normalized(it).matches(Regex("[¥￥]\\d+(?:[.,]\\d{1,2})?")) }
        if (receiptHeader && amount && fields.count { it in setOf("收款方", "付款方", "支付方式", "交易时间", "交易单号") } >= 2) {
            return PageDecision(PageKind.PAYMENT, "receipt_structure")
        }
        // 账单列表不是支付成功页：要求顶栏、年月、收支汇总和同一行的日期/金额共同成立。
        val summary = inBand(0.0, .35).map(::normalized)
        val body = inBand(.25, .95)
        val period = Regex("\\d{4}年\\d{1,2}月")
        val expense = Regex("支出[¥￥]?\\d[\\d,]*(?:\\.\\d{1,2})?")
        val income = Regex("收入[¥￥]?\\d[\\d,]*(?:\\.\\d{1,2})?")
        val signedAmount = Regex("[+-][¥￥]?\\d[\\d,]*(?:\\.\\d{1,2})?")
        val date = Regex("\\d{1,2}月\\d{1,2}日(?:\\d{1,2}:\\d{2})?")
        val datedTransaction = body.any { amountLabel ->
            amountLabel.bounds.left >= bounds.left + width * .5 && signedAmount.matches(normalized(amountLabel)) &&
                body.any { dateLabel -> dateLabel.bounds.left < bounds.left + width * .5 &&
                    date.matches(normalized(dateLabel)) &&
                    kotlin.math.abs(dateLabel.bounds.top - amountLabel.bounds.top) <= height * .08 }
        }
        if (top.contains("账单") && summary.any(period::containsMatchIn) &&
            summary.any(expense::containsMatchIn) && summary.any(income::containsMatchIn) && datedTransaction)
            return PageDecision(PageKind.PAYMENT, "bill_list_structure")
        if (isWechat && miniAppChromeVerified) return PageDecision(PageKind.MINI_APP, "verified_mini_app_chrome")
        val feed = if (isWechat) top.containsAll(setOf("关注", "推荐")) &&
            ((top.any { it == "朋友" || it == "看剧" } && bottom.any { it == "+关注" || it == "关注" }) ||
                top.containsAll(setOf("朋友", "看剧")))
        else top.containsAll(setOf("关注", "推荐")) && bottom.containsAll(setOf("首页", "消息", "我"))
        if (feed) {
            // 抖音普通推荐页顶部同样有“直播”导航，不能把导航入口当当前直播内容。
            if (visible.any { normalized(it) == "正在直播" ||
                    (normalized(it) == "直播" && it.bounds.top >= bounds.top + height * .22) })
                return reject("live_not_single_video")
            val image = visible.any { normalized(it) == "图文" }
            // 只确认信息流页面，不凭导航和播放像素捏造视频类型或跨访问内容ID。
            return PageDecision(if (image) PageKind.IMAGE_POST else PageKind.MEDIA_FEED, "feed_navigation")
        }
        return reject("insufficient_evidence")
    }

    /** 有界且只读可见节点；隐藏父节点的子树也不纳入证据。 */
    fun labels(root: UiNodeSnapshot): List<PageLabel> {
        val result = mutableListOf<PageLabel>()
        var count = 0
        fun visit(node: UiNodeSnapshot, depth: Int) {
            if (++count > 600 || depth > 30 || !node.visibleToUser) return
            val b = node.bounds
            if (b.right <= root.bounds.left || b.left >= root.bounds.right || b.bottom <= root.bounds.top || b.top >= root.bounds.bottom) return
            val text = node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription.orEmpty()
            if (text.isNotBlank() || node.editable || node.password) result += PageLabel(text.take(1000), b, node.editable, node.password)
            for (child in node.children) { if (count >= 600) break; visit(child, depth + 1) }
        }
        visit(root, 0)
        return result
    }
}
