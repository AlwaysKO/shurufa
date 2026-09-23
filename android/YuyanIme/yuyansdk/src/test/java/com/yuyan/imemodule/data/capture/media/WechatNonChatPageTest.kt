package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WechatNonChatPageTest {
    @Test fun rejectsRightAlignedEditorActionWithoutBlacklistingContactName() {
        val header = header()
        menu(header)
        assertTrue(isWechatNonChatHeader(header, listOf(title("编辑标签"), OcrTextLine("完成", 1010, 40, 1150, 106))))
        for (name in listOf("完成", "付款", "登录验证", "编辑标签")) {
            assertFalse(name, isWechatNonChatHeader(header, listOf(title(name))))
        }
        header.recycle()
    }

    @Test fun rejectsMiniProgramCapsuleButKeepsRegularChatMenuInBothThemes() {
        for (dark in listOf(false, true)) for (scale in listOf(1, 2)) {
            val header = header(dark, scale)
            capsule(header)
            assertTrue(isWechatNonChatHeader(header, listOf(title("登录验证", scale))))
            header.recycle()
            val chat = header(dark, scale)
            menu(chat)
            assertFalse(isWechatNonChatHeader(chat, listOf(title("登录验证", scale))))
            chat.recycle()
        }
    }

    @Test fun rejectsWebCloseCrossButDoesNotMistakeBackChevronForClose() {
        val header = header()
        menu(header)
        cross(header)
        assertTrue(isWechatNonChatHeader(header, listOf(title("支付完成"))))
        header.recycle()
        val chat = header()
        menu(chat)
        for (y in 48..96) ink(chat, 44 + abs(y - 72), y)
        assertFalse(isWechatNonChatHeader(chat, listOf(title("支付完成"))))
        chat.recycle()
    }

    @Test fun rejectsOrdinaryPageWithEmptyMenuButKeepsKnownFixedPages() {
        val header = header()
        assertTrue(isWechatNonChatHeader(header, listOf(title("付款"))))
        for (name in listOf("微信", "朋友圈")) {
            assertFalse(isWechatNonChatHeader(header, listOf(title(name))))
        }
        assertFalse(isWechatNonChatHeader(header, emptyList()))
        header.recycle()
    }

    @Test fun doesNotInferMissingMenuFromNonstandardColoredOrTallHeader() {
        val colored = header().apply { eraseColor(Color.BLUE) }
        assertFalse(isWechatNonChatHeader(colored, listOf(title("付款"))))
        colored.recycle()
        val tall = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertFalse(isWechatNonChatHeader(tall, listOf(title("付款"))))
        tall.recycle()
    }

    private fun title(name: String, scale: Int = 1) = OcrTextLine(name, 420 * scale, 40 * scale, 780 * scale, 106 * scale)
    private fun header(dark: Boolean = false, scale: Int = 1) =
        Bitmap.createBitmap(1200 * scale, 144 * scale, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (dark) Color.rgb(17, 17, 17) else Color.WHITE)
        }
    private fun ink(bitmap: Bitmap, x: Int, y: Int) {
        val foreground = if (Color.red(bitmap.getPixel(0, 0)) < 100) Color.WHITE else Color.BLACK
        for (dy in -1..1) for (dx in -1..1) if (x + dx in 0 until bitmap.width && y + dy in 0 until bitmap.height) {
            bitmap.setPixel(x + dx, y + dy, foreground)
        }
    }
    private fun disk(bitmap: Bitmap, x: Double, y: Double, radius: Double) {
        for (py in (y - radius).toInt()..(y + radius).toInt()) for (px in (x - radius).toInt()..(x + radius).toInt()) {
            if ((px - x) * (px - x) + (py - y) * (py - y) <= radius * radius) ink(bitmap, px, py)
        }
    }
    private fun menu(bitmap: Bitmap) {
        val h = bitmap.height.toDouble()
        for (offset in listOf(.36, .51, .66)) disk(bitmap, bitmap.width - h * offset, h * .5, h * .025)
    }
    private fun capsule(bitmap: Bitmap) {
        val h = bitmap.height.toDouble()
        val cx = bitmap.width - h * .72
        val cy = h * .5
        disk(bitmap, cx, cy, h * .055)
        for (y in (cy - h * .21).toInt()..(cy + h * .21).toInt()) {
            for (x in (cx - h * .21).toInt()..(cx + h * .21).toInt()) {
                val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d in (h * .145) * (h * .145)..(h * .20) * (h * .20)) ink(bitmap, x, y)
            }
        }
        for (offset in listOf(.93, 1.10, 1.28)) disk(bitmap, cx - h * offset, cy, h * .025)
    }
    private fun cross(bitmap: Bitmap) {
        for (delta in -20..20) {
            ink(bitmap, 60 + delta, 72 + delta)
            ink(bitmap, 60 + delta, 72 - delta)
        }
    }
}
