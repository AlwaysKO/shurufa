package com.yuyan.imemodule.data.capture.page

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class PageOcrLabelsTest {
    private val bounds = IntRect(0, 0, 1000, 2000)
    private fun label(text: String, x: Int, y: Int, width: Int = 100) = PageLabel(text, IntRect(x, y, x + width, y + 50))
    @Test fun nativeElementsRestoreIndependentNavigationFromUnspacedOcrLine() {
        val line = label("直播关注团购商城推荐", 100, 100, 800)
        val elements = listOf(label("关注", 300, 100), label("推荐", 700, 100))
        val bottom = listOf(label("首页", 20, 1900), label("消息", 600, 1900), label("我", 850, 1900))
        fun classify(labels: List<PageLabel>) = PageCapturePolicy.classify("com.ss.android.ugc.aweme", bounds, labels)
        assertNull(classify(listOf(line) + bottom).kind)
        val labels = pageOcrLineLabels(line, elements)
        assertTrue(line in labels)
        assertEquals(PageKind.MEDIA_FEED, classify(labels + bottom).kind)
        assertNull(classify(pageOcrLineLabels(line.copy(bounds = IntRect(100, 800, 900, 850)),
            elements.map { it.copy(bounds = IntRect(it.bounds.left, 800, it.bounds.right, 850)) }) + bottom).kind)
    }
    @Test fun completeLinesRemainAvailableForSensitivePromptsAndAmounts() {
        val sensitive = label("请输入支付密码", 100, 700, 700)
        val labels = pageOcrLineLabels(sensitive, listOf(label("请输入", 100, 700), label("支付", 300, 700), label("密码", 500, 700)))
        assertEquals("sensitive_input", PageCapturePolicy.classify("com.tencent.mm", bounds, labels).reason)
        val amount = label("¥3.00", 200, 400)
        assertTrue(amount in pageOcrLineLabels(amount, listOf(label("¥", 200, 400), label("3.00", 230, 400))))
        assertEquals(listOf(amount), pageOcrLineLabels(amount, listOf(amount)))
    }
    @Test fun diagnosticContainsFlagsNeverAuthorizeSubstringNavigation() {
        val labels = listOf(label("直播关注商城推荐", 100, 100, 800),
            label("首页", 20, 1900), label("消息", 600, 1900), label("我", 850, 1900))
        val signals = PageCapturePolicy.feedNavigationEvidence(bounds, labels)
        assertTrue(signals.topContainsFollow); assertTrue(signals.topContainsRecommend)
        assertFalse(signals.topFollow); assertFalse(signals.topRecommend)
        assertNull(PageCapturePolicy.classify("com.ss.android.ugc.aweme", bounds, labels).kind)
    }
}
