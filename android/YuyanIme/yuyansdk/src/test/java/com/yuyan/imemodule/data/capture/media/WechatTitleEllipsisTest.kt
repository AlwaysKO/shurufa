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
class WechatTitleEllipsisTest {
    private fun sample(points: Int, dark: Boolean = false, vertical: Boolean = false): Bitmap =
        Bitmap.createBitmap(400,144,Bitmap.Config.ARGB_8888).apply {
            eraseColor(if(dark) Color.BLACK else Color.WHITE)
            for(i in 0 until points) for(y in 0..6) for(x in 0..5) {
                setPixel(190+x+if(vertical)0 else i*10,75+y+if(vertical)i*10 else 0,if(dark)Color.WHITE else Color.BLACK)
            }
        }
    private val line = OcrTextLine("测试.店5337(135)",100,40,360,96,listOf(
        OcrTextSymbol("测试",100,40,180,96),OcrTextSymbol(".",185,40,225,96),
        OcrTextSymbol("店5337(135)",230,40,360,96)))
    @Test fun threeAlignedSmallDotsRecoverEllipsisInBothThemes() {
        for(dark in listOf(false,true)) {
            val bitmap=sample(3,dark)
            val corrected=restoreWechatTitleEllipsis(bitmap,line)
            assertEquals("测试…店5337(135)",corrected.text)
            assertEquals("…",corrected.symbols[1].text)
            bitmap.recycle()
        }
    }
    @Test fun ordinaryDecimalOrEnglishDotIsNotChanged() {
        val bitmap=sample(1)
        assertEquals(line,restoreWechatTitleEllipsis(bitmap,line))
        bitmap.recycle()
    }
    @Test fun twoDotsOrVerticalPunctuationAreNotGuessedAsEllipsis() {
        for(bitmap in listOf(sample(2),sample(3,vertical=true))) {
            assertEquals(line,restoreWechatTitleEllipsis(bitmap,line));bitmap.recycle()
        }
    }
    @Test fun missingSymbolEvidenceDoesNotRewriteEveryPeriod() {
        val bitmap=sample(3)
        val noSymbols=line.copy(symbols=emptyList())
        assertEquals(noSymbols,restoreWechatTitleEllipsis(bitmap,noSymbols));bitmap.recycle()
    }
    @Test fun trailingThreeDotsAreTruncationButStandaloneMenuIsNotATitle() {
        val bitmap=sample(3)
        val trailing=line.copy(text="测试.",symbols=line.symbols.take(2))
        assertEquals("测试…",restoreWechatTitleEllipsis(bitmap,trailing).text)
        val menu=trailing.copy(text=".",symbols=listOf(line.symbols[1]))
        assertEquals(menu,restoreWechatTitleEllipsis(bitmap,menu))
        bitmap.recycle()
    }
    @Test fun additionalInteriorGlyphIsNotIgnoredAsEvidenceForThreeDots() {
        val bitmap=sample(3)
        for(y in 45..64) for(x in 204..218) bitmap.setPixel(x,y,Color.BLACK)
        assertEquals(line,restoreWechatTitleEllipsis(bitmap,line));bitmap.recycle()
    }
}
