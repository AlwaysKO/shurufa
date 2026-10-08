package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WechatTitleRegionTest {
    @Test fun aSmallRealGlyphAfterNumericParenthesesIsNotProofOfAControl() {
        val header = image(true)
        for (y in 48..95) for (x in 900..946) header.setPixel(x, y, header.getPixel(0, 0))
        for (y in 52..90) for (x in 900..936) {
            if (x <= 902 || x >= 934 || y <= 54 || y >= 88) header.setPixel(x, y, Color.WHITE)
        }
        val title = OcrTextLine("项目(2024)口", 185, 48, 947, 96, listOf(
            OcrTextSymbol("项目", 185, 48, 690, 96), OcrTextSymbol("(", 770, 48, 775, 96),
            OcrTextSymbol("2024)", 790, 48, 866, 96), OcrTextSymbol("口", 895, 48, 947, 96)))
        assertNull(wechatTitleEvidenceBounds(header, title))
        header.recycle()
    }

    @Test fun leadingSeparatorRequiresBlankPixelsBeforeItCanBeRemoved() {
        val header = image()
        for (prefix in listOf("|", "｜", "¦")) {
            val line = OcrTextLine(prefix + "联系人", 175, 48, 690, 96, listOf(
                OcrTextSymbol(prefix, 175, 48, 180, 96), OcrTextSymbol("联系人", 185, 48, 690, 96)))
            assertEquals("联系人", wechatTitleEvidenceBounds(header, line)!!.text)
            for (y in 48..95) for (x in 175..178) header.setPixel(x, y, Color.BLACK)
            assertEquals(prefix + "联系人", wechatTitleEvidenceBounds(header, line)!!.text)
            for (y in 48..95) for (x in 175..178) header.setPixel(x, y, header.getPixel(0, 0))
            assertNull(wechatTitleEvidenceBounds(header, line.copy(symbols = emptyList())))
        }
        header.recycle()
    }

    @Test fun legacyScreenshotWithoutExactTitleBandDoesNotConfirmAmbiguousControlText() {
        val header = image(true)
        val tracker = WechatTitleStabilizer()
        val title = OcrTextLine("一家人(223)应", 185, 48, 947, 96)
        for (now in listOf(1000L, 1800L, 2600L)) {
            val evidence = wechatScreenshotTitleEvidence(header, title, exactBand = false)
            val visual = evidence?.let { wechatTitlePixelSignature(header, it) }
            val identity = tracker.observe(title.text, visual, now)
            assertEquals("pending", identity.status)
        }
        assertNotNull(wechatScreenshotTitleEvidence(header, title.copy(text = "正常联系人"), exactBand = false))
        header.recycle()
    }

    @Test fun smallControlTouchingCountCannotProvideIdentityEvidence() {
        val header = image(true)
        for (y in 52..90) for (x in 866..936) header.setPixel(x, y, Color.WHITE)
        val title = OcrTextLine("一家人(223)应", 185, 48, 947, 96, listOf(
            OcrTextSymbol("一家人", 185, 48, 690, 96), OcrTextSymbol("(", 770, 48, 775, 96),
            OcrTextSymbol("223)", 790, 48, 902, 96), OcrTextSymbol("应", 900, 48, 947, 96)))
        assertNull(wechatScreenshotTitleEvidence(header, title, exactBand = true))
        header.recycle()
    }

    @Test fun sameContrastSmallControlAfterCountIsExcludedInBothThemes() {
        for (dark in listOf(false, true)) {
            val header = image(dark)
            val background = header.getPixel(0, 0)
            val ink = if (dark) Color.WHITE else Color.BLACK
            for (y in 48..95) for (x in 900..946) header.setPixel(x, y, background)
            drawMutedBell(header, 900, 52, ink)
            val clean = OcrTextLine("一家人(223)", 185, 48, 866, 96, listOf(
                OcrTextSymbol("一家人", 185, 48, 690, 96), OcrTextSymbol("(", 770, 48, 775, 96),
                OcrTextSymbol("223)", 790, 48, 866, 96)))
            val noisy = clean.copy(text = "|一家人(223)应", right = 947,
                symbols = listOf(OcrTextSymbol("|", 175, 48, 180, 96)) + clean.symbols +
                    OcrTextSymbol("应", 895, 48, 947, 96))
            // 框可以包含空白；采用实际像素尺寸，不能只比较OCR行框。
            val expected = wechatTitleEvidenceBounds(header, clean)!!
            val evidence = wechatTitleEvidenceBounds(header, noisy)!!
            assertEquals("一家人(223)", evidence.text)
            val visual = wechatNicknamePixelSignature(header, evidence)
            assertEquals(wechatNicknamePixelSignature(header, expected), visual)
            val tracker = WechatTitleStabilizer()
            tracker.observe(evidence.text, visual, 1000)
            val confirmed = tracker.observe(evidence.text, visual, 1800)
            assertEquals("confirmed", confirmed.status)
            assertEquals("一家人", confirmed.displayName)
            assertEquals(com.yuyan.imemodule.data.capture.model.ConversationType.GROUP, confirmed.conversationType)
            header.recycle()
        }
    }

    @Test fun ambiguousCountTrailerWithoutSymbolEvidenceCannotConfirmAName() {
        val header = image(true)
        val title = OcrTextLine("一家人(223)应", 185, 48, 947, 96)
        assertNull(wechatTitleEvidenceBounds(header, title))
        header.recycle()
    }

    @Test fun ambiguousCountTrailerWithUnmatchedSymbolsCannotConfirmAName() {
        val header = image(true)
        val title = OcrTextLine("一家人(223)应", 185, 48, 947, 96,
            listOf(OcrTextSymbol("一家人(223)", 185, 48, 866, 96)))
        assertNull(wechatTitleEvidenceBounds(header, title))
        header.recycle()
    }

    @Test fun preparedTitleIsEnlargedAndAllRecognitionBoxesReturnToOriginalCoordinates() = kotlinx.coroutines.runBlocking {
        val header = image()
        var input: Bitmap? = null
        val line = OcrTextLine("煌家112Lucky王", 370, 96, 1380, 192,
            listOf(OcrTextSymbol("Lucky", 900, 100, 1250, 190)))
        try {
            val result = recognizePreparedWechatTitleHeader(header) { enlarged ->
                input = enlarged
                assertEquals(2400, enlarged.width)
                assertEquals(288, enlarged.height)
                listOf(line)
            }.single()
            assertEquals(OcrTextLine("煌家112Lucky王", 185, 48, 690, 96,
                listOf(OcrTextSymbol("Lucky", 450, 50, 625, 95))), result)
            assertTrue(input!!.isRecycled)
            assertFalse(header.isRecycled)
        } finally { header.recycle() }
    }

    @Test fun enlargedHeaderIsReleasedWhenRecognitionFails() = kotlinx.coroutines.runBlocking {
        val header = image()
        var input: Bitmap? = null
        try {
            try {
                recognizePreparedWechatTitleHeader(header) { input = it; error("recognition failed") }
                fail("expected recognition failure")
            } catch (_: IllegalStateException) { }
            assertTrue(input!!.isRecycled)
            assertFalse(header.isRecycled)
        } finally { header.recycle() }
    }

    private fun image(dark: Boolean = false): Bitmap = Bitmap.createBitmap(1200, 144, Bitmap.Config.ARGB_8888).apply {
        eraseColor(if (dark) Color.rgb(25,25,25) else Color.rgb(237,237,237))
        val ink = if (dark) Color.WHITE else Color.BLACK
        fun block(left: Int, right: Int, color: Int) {
            for (y in 48..95) for (x in left until right) setPixel(x,y,color)
        }
        block(50,78,ink) // 返回
        block(100,120,ink) // 未读数
        for (x in 185..675 step 25) block(x,x+15,ink) // 普通中英数字字形
        block(700,705,ink) // Emoji黑色轮廓，与正常文字分开
        block(705,750,Color.RED)
        block(770,775,ink) // 群人数左括号
        block(790,855,ink)
        block(860,866,ink)
        block(900,947,Color.GREEN) // 功能图标
        block(1100,1130,ink) // 菜单
    }
    private fun drawMutedBell(header: Bitmap, left: Int, top: Int, ink: Int) {
        fun stroke(x0: Int, y0: Int, x1: Int, y1: Int) {
            val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)).coerceAtLeast(1)
            for (step in 0..steps) {
                val x = x0 + (x1 - x0) * step / steps
                val y = y0 + (y1 - y0) * step / steps
                for (py in maxOf(0, y - 1)..minOf(38, y + 1))
                    for (px in maxOf(0, x - 1)..minOf(36, x + 1)) header.setPixel(left + px, top + py, ink)
            }
        }
        stroke(16, 0, 19, 4)
        stroke(14, 4, 7, 9); stroke(7, 9, 5, 17); stroke(5, 17, 5, 29); stroke(5, 29, 1, 34)
        stroke(19, 4, 25, 9); stroke(25, 9, 26, 26)
        stroke(1, 34, 28, 34); stroke(16, 37, 18, 37)
        stroke(3, 5, 36, 38)
    }
    @Test fun removesNavigationAndColoredControlsButPreservesTextInBothThemes() {
        for (dark in listOf(false,true)) {
            val original = image(dark)
            val processed = prepareWechatTitleHeader(original)
            val background = original.getPixel(0,0)
            assertEquals(background,processed.getPixel(60,60))
            assertEquals(background,processed.getPixel(110,60))
            assertEquals(background,processed.getPixel(702,60))
            assertEquals(background,processed.getPixel(720,60))
            assertEquals(background,processed.getPixel(920,60))
            assertEquals(background,processed.getPixel(1110,60))
            assertEquals(original.getPixel(190,60),processed.getPixel(190,60))
            assertEquals(original.getPixel(680,60),processed.getPixel(680,60))
            assertEquals(original.getPixel(800,60),processed.getPixel(800,60))
            assertEquals(Color.RED,original.getPixel(720,60)) // 不修改原始证据
            processed.recycle(); original.recycle()
        }
    }
    @Test fun textAfterAnEmojiIsNotTruncated() {
        val original = image()
        for (y in 48..95) for (x in 820..840) original.setPixel(x,y,Color.BLACK)
        val processed = prepareWechatTitleHeader(original)
        assertEquals(Color.BLACK,processed.getPixel(830,60))
        processed.recycle(); original.recycle()
    }
    @Test fun pureTextAndAntialiasPixelsRemainUnchanged() {
        val original = image()
        original.setPixel(201,60,Color.rgb(120,120,120))
        val processed = prepareWechatTitleHeader(original)
        assertEquals(original.getPixel(201,60),processed.getPixel(201,60))
        processed.recycle(); original.recycle()
    }
    @Test fun groupCountChangesAndOcrBoundsDoNotChangeOriginalNicknameEvidence() {
        val first = image()
        val line = OcrTextLine("小组(279)",185,46,866,103, listOf(
            OcrTextSymbol("小组",185,48,690,96), OcrTextSymbol("(",770,48,775,96),
            OcrTextSymbol("279)",790,48,866,96)))
        val bounds = wechatTitleEvidenceBounds(first,line)!!
        val signature = wechatNicknamePixelSignature(first,bounds)
        val changed = image()
        for (y in 48..95) for (x in 790..854) changed.setPixel(x,y,Color.rgb(237,237,237))
        val nextBounds = wechatTitleEvidenceBounds(changed,line.copy(top=43,bottom=105,left=165,text="小组(2)"))!!
        assertNotNull(signature)
        assertEquals(signature,wechatNicknamePixelSignature(changed,nextBounds))
        first.recycle(); changed.recycle()
    }
    @Test fun separatedDimControlAfterGroupCountDoesNotChangeNicknameEvidence() {
        val header = image()
        val clean = OcrTextLine("一路江湖(210)",185,48,866,96, listOf(
            OcrTextSymbol("一路江湖",185,48,690,96), OcrTextSymbol("(",770,48,775,96),
            OcrTextSymbol("210)",790,48,866,96)))
        // 模拟右侧灰色静音控件被读成汉字；不是删除任意中文尾字。
        for (y in 48..95) for (x in 900..946) header.setPixel(x,y,header.getPixel(0,0))
        drawMutedBell(header, 900, 52, Color.rgb(155,155,155))
        val noisy = clean.copy(text="一路江湖(210)应",right=947,
            symbols=clean.symbols + OcrTextSymbol("应",900,48,947,96))
        val first = wechatTitleEvidenceBounds(header,clean)!!
        val second = wechatTitleEvidenceBounds(header,noisy)!!
        assertEquals("一路江湖(210)",second.text)
        assertEquals(wechatNicknamePixelSignature(header,first),wechatNicknamePixelSignature(header,second))
        val overlapping = noisy.copy(symbols = noisy.symbols.map { symbol ->
            if (symbol.text == "210)") symbol.copy(right=902) else symbol
        })
        assertEquals("一路江湖(210)", wechatTitleEvidenceBounds(header,overlapping)!!.text)
        // 同字号深色汉字也可能是图标；保留原始观测，但不能仅凭字号确认或截掉它。
        for (y in 48..95) for (x in 900..946) header.setPixel(x,y,Color.BLACK)
        assertNull(wechatTitleEvidenceBounds(header,noisy))
        header.recycle()
    }

    @Test fun originalEmojiAndSingleGlyphDifferencesRemainDistinctIdentityEvidence() {
        val first = image()
        val line = OcrTextLine("小组(279)",185,46,866,103, listOf(
            OcrTextSymbol("小组",185,48,690,96), OcrTextSymbol("(",770,48,775,96),
            OcrTextSymbol("279)",790,48,866,96)))
        val bounds = wechatTitleEvidenceBounds(first,line)!!
        val signature = wechatNicknamePixelSignature(first,bounds)
        first.setPixel(730,60,Color.rgb(237,237,237))
        assertNotEquals(signature,wechatNicknamePixelSignature(first,bounds))
        first.setPixel(730,60,Color.RED)
        first.setPixel(190,60,Color.rgb(237,237,237))
        assertNotEquals(signature,wechatNicknamePixelSignature(first,bounds))
        first.recycle()
    }
    @Test fun unprovenCountBoundaryDoesNotProvideAStableVisualKey() {
        val first = image()
        assertNull(wechatTitleEvidenceBounds(first,OcrTextLine("小组(279)",185,46,866,103)))
        first.recycle()
    }
    @Test fun navigationMaskMustNotCutATitleCrossingItsBoundary() {
        val original = image()
        for (y in 48..95) for (x in 140..170) original.setPixel(x,y,Color.BLACK)
        val processed = prepareWechatTitleHeader(original)
        assertEquals(Color.BLACK,processed.getPixel(145,60))
        processed.recycle(); original.recycle()
    }
    @Test fun saturatedTextThemeIsNotErasedAsIcons() {
        val original = image()
        for (y in 48..95) for (x in 185..690) if(original.getPixel(x,y)==Color.BLACK) original.setPixel(x,y,Color.BLUE)
        val processed = prepareWechatTitleHeader(original)
        assertEquals(Color.BLUE,processed.getPixel(190,60))
        processed.recycle(); original.recycle()
    }
    @Test fun iconWithoutReliableGapMustNotEraseTouchingText() {
        val original = image()
        for (y in 48..95) for (x in 675..705) original.setPixel(x,y,Color.BLACK)
        val processed = prepareWechatTitleHeader(original)
        assertEquals(Color.BLACK,processed.getPixel(690,60))
        processed.recycle(); original.recycle()
    }
    @Test fun internalParenthesesAreNotACountSuffix() {
        val original = image()
        val bounds = wechatTitleEvidenceBounds(original, OcrTextLine("项目(2024)讨论组",185,48,690,96))!!
        assertTrue(bounds.right > 1000)
        original.recycle()
    }
    @Test fun clippedGlyphsCannotBeUsedAsIdentityEvidence() {
        val original = image()
        assertNull(wechatTitleEvidenceBounds(original,OcrTextLine("联系人",185,1,690,96)))
        original.recycle()
    }
    @Test fun sameEmojiShapeWithDifferentColorMustNotMergeAfterDisplayCleaning() {
        val original = image()
        val bounds = wechatTitleEvidenceBounds(original,OcrTextLine("测试群",185,48,690,96))!!
        val red = wechatNicknamePixelSignature(original,bounds)
        for (y in 48..95) for (x in 705 until 750) original.setPixel(x,y,Color.BLUE)
        val blue = wechatNicknamePixelSignature(original,bounds)
        assertNotEquals(red,blue)
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("测试群",red,1000)
        val second = tracker.observe("测试群",blue,1800)
        assertNotEquals(first.externalKey,second.externalKey)
        original.recycle()
    }
}
