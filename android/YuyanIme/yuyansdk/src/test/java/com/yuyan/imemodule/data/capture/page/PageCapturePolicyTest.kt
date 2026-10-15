package com.yuyan.imemodule.data.capture.page

import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import org.junit.Assert.*
import org.junit.Test

class PageCapturePolicyTest {
    private val bounds = IntRect(0, 0, 1000, 2000)
    private fun label(text: String, x: Int, y: Int) = PageLabel(text, IntRect(x, y, x + 110, y + 50))
    private fun classify(labels: List<PageLabel>, pkg: String = "com.tencent.mm", chat: Boolean = false) =
        PageCapturePolicy.classify(pkg, bounds, labels, chatVerified = chat)
    private fun listLabels() = listOf(label("微信", 50, 1900), label("通讯录", 280, 1900),
        label("发现", 550, 1900), label("我", 820, 1900), label("微信", 420, 110))
    private fun feedLabels() = listOf(label("关注", 240, 110), label("朋友", 450, 110),
        label("推荐", 650, 110), label("+关注", 300, 1850))

    @Test fun wechatConversationListUsesNavigationNotConversationNames() {
        assertEquals(PageKind.CONVERSATION_LIST, classify(listLabels()).kind)
    }
    @Test fun legacyListTitleAliasesStillRequireNavigationAndCannotOverrideChat() {
        for (title in listOf("微佳", "微信(12)")) {
            val labels = listLabels().dropLast(1) + label(title, 420, 110)
            assertEquals(PageKind.CONVERSATION_LIST, classify(labels).kind)
            assertNull(classify(labels.filterNot { it.text == "通讯录" }).kind)
            assertEquals(PageKind.CHAT, classify(labels, chat = true).kind)
        }
    }

    @Test fun douyinMessageListRequiresHeaderAndBottomNavigation() {
        val labels = listOf(label("消息", 450, 110), label("首页", 20, 1900), label("朋友", 210, 1900),
            label("消息", 640, 1900), label("我", 850, 1900))
        assertEquals(PageKind.CONVERSATION_LIST, classify(labels, "com.ss.android.ugc.aweme").kind)
    }
    @Test fun douyinLiveNavigationTabDoesNotMakeOrdinaryFeedALiveBroadcast() {
        val labels = listOf(label("直播", 160, 110), label("关注", 300, 110), label("推荐", 700, 110),
            label("首页", 20, 1900), label("消息", 640, 1900), label("我", 850, 1900))
        assertEquals(PageKind.MEDIA_FEED, classify(labels, "com.ss.android.ugc.aweme").kind)
        assertNull(classify(labels + label("正在直播", 300, 850), "com.ss.android.ugc.aweme").kind)
        assertNull(classify(labels + label("直播", 300, 850), "com.ss.android.ugc.aweme").kind)
    }

    @Test fun paymentResultRequiresResultAndReceiptFieldsTogether() {
        val labels = listOf(label("支付成功", 420, 250), label("¥3.00", 400, 380),
            label("收款方", 100, 750), label("支付方式", 100, 850))
        assertEquals(PageKind.PAYMENT, classify(labels).kind)
        assertNull(classify(listOf(label("支付成功", 100, 750))).kind)
    }
    private fun paymentCards() = listOf(label("微信支付", 420, 110),
        label("我的账单", 150, 1900), label("支付服务", 450, 1900), label("优惠", 750, 1900),
        label("¥5.00", 400, 1280), label("交易状态", 80, 1460),
        label("支付成功，对方已收款", 280, 1460), label("查看交易详情", 80, 1600))

    @Test fun wechatPaymentServiceCardsRequireNavigationAndTransactionEvidence() {
        assertEquals(PageKind.PAYMENT, classify(paymentCards()).kind)
        assertNull(classify(paymentCards().filterNot { it.text == "微信支付" }).kind)
        assertNull(classify(paymentCards().filterNot { it.text == "支付服务" }).kind)
        assertNull(classify(paymentCards().filterNot { it.text == "¥5.00" }).kind)
        assertNull(classify(paymentCards().filterNot { it.text == "查看交易详情" }).kind)
        assertNull(classify(paymentCards(), "com.ss.android.ugc.aweme").kind)
        assertEquals(PageKind.CHAT, classify(paymentCards(), chat = true).kind)
        assertEquals("sensitive_input", classify(paymentCards() + label("请输入支付密码", 100, 600)).reason)
    }

    private fun orderList() = listOf(label("商品名/订单号/快递单号", 120, 110),
        label("全部", 30, 260), label("待支付", 220, 260), label("待发货", 420, 260),
        label("待收货/使用", 600, 260), label("评价", 820, 260),
        label("交易完成", 800, 610), label("¥89.90", 800, 700), label("合计¥89.90", 700, 870),
        label("删除订单", 330, 1070), label("再来一单", 600, 1070), label("去评价", 800, 1070))

    @Test fun realWechatOcrMayOmitCurrencyAndMergeStatusLine() {
        val labels = listOf(label("微信支付", 420, 110), label("我的账单", 150, 1900),
            label("支付服务", 450, 1900), label("优惠", 750, 1900),
            PageLabel("使用零钱通支付", IntRect(370,1239,625,1271)),
            PageLabel("5.00", IntRect(435,1323,618,1399)),
            PageLabel("|交易状态支付成功,对方已收款", IntRect(62,1504,658,1542)),
            label("查看交易详倩", 80,1641))
        assertEquals(PageKind.PAYMENT, classify(labels).kind)
        assertNull(classify(labels.filterNot { it.text.startsWith("使用") }).kind)
        assertNull(classify(labels.filterNot { it.text.startsWith("|交易状态") }).kind)
        assertNull(classify(labels.filterNot { it.text == "5.00" }).kind)
        assertNull(classify(labels.map { if (it.text == "5.00") it.copy(bounds=IntRect(435,600,618,650)) else it }).kind)
        assertNull(classify(labels + label("请输入支付密码", 100,600)).kind)
    }

    @Test fun douyinOrderListRequiresStatusTabsAndActualOrderCard() {
        val pkg = "com.ss.android.ugc.aweme"
        assertEquals(PageKind.PAYMENT, classify(orderList(), pkg).kind)
        for (missing in listOf("待支付", "待发货", "待收货/使用", "交易完成"))
            assertNull(missing, classify(orderList().filterNot { it.text == missing }, pkg).kind)
        assertNull(classify(orderList().filterNot { it.text.contains("¥") }, pkg).kind)
        assertNull(classify(orderList().filterNot { it.text in setOf("删除订单", "再来一单", "去评价") }, pkg).kind)
        assertNull(classify(orderList()).kind)
        assertNull(classify(orderList().map { if (it.bounds.top < 300) it.copy(bounds=IntRect(100,900,800,950)) else it }, pkg).kind)
        assertEquals("sensitive_input", classify(orderList() + label("请输入验证码", 100, 600), pkg).reason)
    }
    @Test fun billListRequiresHeaderPeriodSummariesAndDatedSignedTransaction() {
        val labels = listOf(label("账单", 420, 100), label("2026年10月", 100, 350),
            label("支出¥3.00 收入¥0.00", 100, 430), label("-3.00", 750, 750),
            label("10月09日 08:55", 100, 810))
        assertEquals(PageKind.PAYMENT, classify(labels).kind)
        assertNull(classify(labels.filterNot { it.text.startsWith("2026") }).kind)
        assertNull(classify(labels.filterNot { it.text.startsWith("支出") }).kind)
        assertNull(classify(labels.filterNot { it.text.startsWith("10月") }).kind)
        assertNull(classify(labels + label("请输入支付密码", 200, 700)).kind)
        assertEquals(PageKind.CHAT, classify(labels, chat = true).kind)
    }

    @Test fun messageBodyMentioningVideoOrPaymentCannotBecomeAnotherPage() {
        val body = listOf(label("支付成功", 100, 750), label("视频号", 100, 850), label("¥3.00", 100, 950))
        assertEquals(PageKind.CHAT, classify(body, chat = true).kind)
        assertNull(classify(body).kind)
    }
    @Test fun unknownEmptyTreeIsNotAnAutomaticPermissionToCapture() {
        assertNull(classify(emptyList()).kind)
        assertEquals("insufficient_evidence", classify(emptyList()).reason)
    }
    @Test fun sensitiveInputOverridesOtherwiseValidList() {
        val labels = listLabels() + PageLabel("", IntRect(100, 500, 900, 700), password = true)
        assertEquals("sensitive_input", classify(labels).reason)
        assertNull(classify(labels).kind)
    }
    @Test fun paymentPasswordAndVerificationPromptsAreRejectedBeforePageRouting() {
        for (prompt in listOf("请输入支付密码", "请输入短信验证码")) {
            val result = classify(listLabels() + label(prompt, 200, 700))
            assertNull(result.kind); assertEquals("sensitive_input", result.reason)
        }
    }
    @Test fun unsupportedApplicationAndSecureWindowNeverCapture() {
        assertNull(classify(listLabels(), "com.tencent.mobileqq").kind)
        assertNull(PageCapturePolicy.classify("com.tencent.mm", bounds, listLabels(), secureWindow = true).kind)
    }
    @Test fun ordinaryEditableNonChatPageIsNotCaptured() {
        assertNull(classify(listLabels() + PageLabel("搜索", IntRect(100, 200, 900, 280), editable = true)).kind)
    }
    @Test fun wechatFeedIsMediaOfUnknownTypeNotAnInventedVideoIdentity() {
        val result = classify(feedLabels())
        assertEquals(PageKind.MEDIA_FEED, result.kind)
        assertNull(result.contentKey)
    }
    @Test fun sameFrameOcrNavigationMayContainSeveralWhitespaceSeparatedTabs() {
        val labels = listOf(label("关注  推荐", 160, 120), label("首页  消息  我", 10, 1900))
        assertEquals(PageKind.MEDIA_FEED, classify(labels, "com.ss.android.ugc.aweme").kind)
        assertNull(classify(labels.map { it.copy(bounds = IntRect(100, 800, 900, 850)) },
            "com.ss.android.ugc.aweme").kind)
        assertNull(classify(listOf(label("关注推荐", 160, 120), label("首页消息我", 10, 1900)),
            "com.ss.android.ugc.aweme").kind)
    }
    @Test fun wechatAlreadyFollowedAuthorStillHasVerifiedFourTabFeedNavigation() {
        val tabs = listOf(label("关注", 120, 110), label("看剧", 300, 110),
            label("朋友", 480, 110), label("推荐", 650, 110))
        assertEquals(PageKind.MEDIA_FEED, classify(tabs).kind)
        assertEquals(PageKind.MEDIA_FEED, classify(listOf(label("关注 看剧 朋友 推荐", 120, 110))).kind)
        assertNull(classify(tabs.filterNot { it.text == "看剧" }).kind)
        assertNull(classify(tabs + label("请输入支付密码", 200, 700)).kind)
    }

    @Test fun wechatNativeOcrMayJoinNavigationTabsWithIconPunctuation() {
        val frameBounds = IntRect(0, 0, 1200, 2664)
        val tabs = listOf(
            PageLabel("关注", IntRect(270, 149, 389, 202)),
            PageLabel("看剧", IntRect(446, 149, 547, 202)),
            PageLabel("朋友-推荐,Q.", IntRect(597, 149, 1046, 202)),
        )
        val result = PageCapturePolicy.classify("com.tencent.mm", frameBounds, tabs)
        assertEquals(PageKind.MEDIA_FEED, result.kind)
        assertNull(result.contentKey)
        val evidence = PageCapturePolicy.feedNavigationEvidence(frameBounds, tabs)
        assertTrue(evidence.topFriend)
        assertTrue(evidence.topRecommend)
    }

    @Test fun punctuatedNavigationCannotBypassPageGeometryOrSensitiveInput() {
        val tabs = listOf(label("关注", 120, 110), label("看剧", 300, 110),
            label("朋友-推荐,Q.", 480, 110))
        assertEquals(PageKind.MEDIA_FEED, classify(tabs).kind)
        assertNull(classify(tabs.map { it.copy(bounds = IntRect(100, 800, 900, 850)) }).kind)
        assertNull(classify(tabs + label("请输入支付密码", 200, 700)).kind)
        assertNull(classify(tabs.filterNot { it.text == "看剧" }).kind)
        assertNull(classify(tabs.map { if (it.text.startsWith("朋友")) it.copy(text = "朋友推荐") else it }).kind)
        assertNull(classify(tabs.map { if (it.text.startsWith("朋友")) it.copy(text = "朋友-推荐理由") else it }).kind)
    }

    @Test fun diagnosticFlagsRevealCountBadgeWithoutChangingClassification() {
        val labels = listOf(label("关注 推荐", 160, 110), label("首页", 20, 1900),
            label("消息4", 640, 1900), label("我", 850, 1900))
        assertNull(classify(labels, "com.ss.android.ugc.aweme").kind)
        val evidence = PageCapturePolicy.feedNavigationEvidence(bounds, labels)
        assertEquals(4, evidence.labelCount)
        assertTrue(evidence.topFollow); assertTrue(evidence.topRecommend)
        assertTrue(evidence.bottomHome); assertFalse(evidence.bottomMessage); assertTrue(evidence.bottomMe)
    }

    @Test fun douyinImagePostIsNotReportedAsKnownVideo() {
        val labels = listOf(label("关注", 160, 120), label("推荐", 550, 120), label("图文", 250, 1500),
            label("首页", 10, 1900), label("消息", 600, 1900), label("我", 840, 1900))
        assertEquals(PageKind.IMAGE_POST, classify(labels, "com.ss.android.ugc.aweme").kind)
    }
    @Test fun labelsInChatBodyCannotImpersonateNavigationTabs() {
        val labels = listLabels().map { it.copy(bounds = IntRect(it.bounds.left, 700, it.bounds.right, 750)) }
        assertNull(classify(labels).kind)
    }
    @Test fun treeEvidenceDoesNotIncludeHiddenOrOffscreenChildren() {
        val hidden = UiNodeSnapshot(null, null, "请输入支付密码", null, bounds, emptyList(), visibleToUser = false)
        val root = UiNodeSnapshot(null, null, null, null, bounds, listOf(hidden))
        assertTrue(PageCapturePolicy.labels(root).isEmpty())
    }
    @Test fun miniAppNeedsVerifiedCapsuleNotJustSomeWebContent() {
        assertNull(classify(listOf(label("小程序", 100, 900))).kind)
        assertEquals(PageKind.MINI_APP, PageCapturePolicy.classify("com.tencent.mm", bounds, emptyList(),
            miniAppChromeVerified = true).kind)
    }
    @Test fun promptVariantsAndTraditionalTextCannotBypassSafetyGate() {
        for (prompt in listOf("请输入支付密码：", "请输入6位支付密码", "请填写验证码", "請輸入支付密碼")) {
            val result = classify(listLabels() + label(prompt, 200, 700))
            assertEquals(prompt, "sensitive_input", result.reason)
            assertNull(result.kind)
        }
    }
    @Test fun adjacentOcrFragmentsAreCombinedOnlyForSafetyRejection() {
        val result = classify(listLabels() + listOf(label("请输入", 200, 650), label("支付密码", 200, 710)))
        assertNull(result.kind); assertEquals("sensitive_input", result.reason)
    }
    @Test fun passwordLabelWithNumericKeypadIsRejectedWithoutReadingEnteredDigits() {
        val keypad = (1..9).map { label(it.toString(), 100 + (it % 3) * 250, 1100 + (it / 3) * 140) }
        assertNull(classify(listLabels() + label("支付密码", 200, 700) + keypad).kind)
    }

}
