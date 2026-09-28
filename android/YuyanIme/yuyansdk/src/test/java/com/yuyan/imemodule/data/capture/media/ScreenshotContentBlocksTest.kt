package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ScreenshotContentBlocksTest {
    private fun sample(height: Int = 300, offset: Int = 0, values: List<Int> = listOf(Color.BLACK, Color.BLUE)): Bitmap =
        Bitmap.createBitmap(200,height,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.LTGRAY)
            values.forEachIndexed { i,color ->
                for (y in 30+i*65+offset until 60+i*65+offset) if(y in 0 until height)
                    for(x in 30..135) setPixel(x,y,color)
            }
        }
    private fun blocks(b: Bitmap) = screenshotContentBlocks(b,IntRect(0,0,b.width,b.height))
    @Test fun verticalTranslationAndKeyboardHeightKeepOrderedContent() {
        val a=sample(); val b=sample(240,30)
        try { assertNotNull(blocks(a)); assertEquals(blocks(a)!!.hashes,blocks(b)!!.hashes) }
        finally {a.recycle();b.recycle()}
    }
    @Test fun repeatedMessageCountShortMessageAndSinglePixelAreRetained() {
        val a=sample();val b=sample(values=listOf(Color.BLACK,Color.BLUE,Color.BLUE))
        try {
            assertEquals(2,blocks(a)!!.hashes.size); assertEquals(3,blocks(b)!!.hashes.size)
            val before=blocks(a)!!.hashes
            a.setPixel(70,40,Color.rgb(1,0,0));assertNotEquals(before,blocks(a)!!.hashes)
            a.setPixel(40,190,Color.BLACK);assertEquals(3,blocks(a)!!.hashes.size)
        }finally{a.recycle();b.recycle()}
    }
    @Test fun unstableBackgroundAndEmptyBodiesDoNotProveDuplication() {
        val wallpaper=sample();val empty=sample(values=emptyList())
        try {
            wallpaper.setPixel(0,80,Color.RED);assertNull(blocks(wallpaper))
            assertNull(blocks(empty))
        }finally{wallpaper.recycle();empty.recycle()}
    }
    @Test fun retainsEveryClippedRowWhileUsingOnlyCompleteBlocksAsAnchors() {
        val full = sample(height = 320, values = listOf(Color.RED, Color.BLACK, Color.BLUE, Color.GREEN))
        try {
            val all = blocks(full)!!
            val clipped = screenshotContentBlocks(full, IntRect(0, 45, 200, 245))
            assertNotNull(clipped)
            assertTrue(clipped!!.hasClippedEdges)
            assertEquals(all.hashes.subList(1, 3), clipped.hashes)
            assertEquals(200 * 32, clipped.rowHashes.size)
            assertArrayEquals(all.rowHashes.copyOfRange(45 * 32, 245 * 32), clipped.rowHashes)
            val before = clipped.rowHashes.copyOf()
            full.setPixel(80, 45, Color.MAGENTA)
            val changed = screenshotContentBlocks(full, IntRect(0, 45, 200, 245))!!
            assertEquals(clipped.hashes, changed.hashes)
            assertFalse(before.contentEquals(changed.rowHashes))
        } finally { full.recycle() }
    }

    @Test fun rowEvidenceIncludesBlankRowsAndTinyChangesInBottomFragment() {
        val full = sample(height = 320, values = listOf(Color.RED, Color.BLACK, Color.BLUE, Color.GREEN))
        try {
            val a = screenshotContentBlocks(full, IntRect(0, 45, 200, 245))!!
            full.setPixel(80, 244, Color.rgb(0, 254, 0))
            val b = screenshotContentBlocks(full, IntRect(0, 45, 200, 245))!!
            assertEquals(a.hashes, b.hashes)
            assertFalse(a.rowHashes.contentEquals(b.rowHashes))
            assertEquals(200 * 32, b.rowHashes.size)
            assertTrue(b.hasClippedEdges)
        } finally { full.recycle() }
    }

    @Test fun leftRightPositionAndColorsRemainPartOfEvidence() {
        val a=sample();val b=sample(values=listOf(Color.BLACK,Color.RED))
        try {assertNotEquals(blocks(a)!!.hashes,blocks(b)!!.hashes)}finally{a.recycle();b.recycle()}
    }
}
