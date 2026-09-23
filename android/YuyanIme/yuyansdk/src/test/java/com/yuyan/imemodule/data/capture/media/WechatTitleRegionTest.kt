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
