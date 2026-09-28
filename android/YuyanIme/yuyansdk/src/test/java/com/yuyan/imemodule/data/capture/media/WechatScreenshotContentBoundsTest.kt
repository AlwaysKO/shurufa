package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WechatScreenshotContentBoundsTest {
    private fun sample(density: Float = 1f, dark: Boolean = false): Bitmap =
        Bitmap.createBitmap((360 * density).toInt(), (800 * density).toInt(), Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (dark) Color.rgb(30, 30, 30) else Color.rgb(238, 238, 238))
            val canvas = Canvas(this).apply { scale(density, density) }
            canvas.drawRect(30f, 100f, 220f, 140f, Paint().apply { color = Color.rgb(80, 180, 80) })
            canvas.drawRect(180f, 240f, 340f, 280f, Paint().apply { color = Color.WHITE })
        }

    private fun input(density: Float = 1f, enabled: Boolean = true) =
        ScreenshotContentInput((56 * density).toInt(), detectBlocks = true, detectWechatBody = enabled)

    @Test fun verifiedThinScrollBarAndNoBarUseIdenticalBodyWidthAndBlocks() {
        for (density in listOf(1f, 2.5f, 3.5f)) for (dark in listOf(false, true)) {
            val bitmap = sample(density, dark)
            val input = input(density)
            try {
                input.captureFrom(bitmap, density)
                val noBar = input.blocks
                assertNotNull(noBar)
                assertEquals(bitmap.width - kotlin.math.ceil(5 * density).toInt(), noBar!!.width)
                val fullHash = input.sha256
                val canvas = Canvas(bitmap).apply { scale(density, density) }
                canvas.drawRect(356f, 180f, 360f, 680f,
                    Paint().apply { color = if (dark) Color.rgb(105, 105, 105) else Color.rgb(160, 160, 160) })
                input.captureFrom(bitmap, density)
                assertNotNull(input.blocks)
                assertEquals(noBar.width, input.blocks!!.width)
                assertEquals(noBar.hashes, input.blocks!!.hashes)
                assertNotEquals(fullHash, input.sha256)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun coloredOrShortContentInRightLaneIsNeverDiscarded() {
        for (kind in listOf("colored", "short", "inset", "split", "neighbor")) {
            val bitmap = sample()
            try {
                val canvas = Canvas(bitmap)
                val paint = Paint().apply { color = Color.rgb(160, 160, 160) }
                when (kind) {
                    "colored" -> { paint.color = Color.RED; canvas.drawRect(356f, 180f, 359f, 680f, paint) }
                    "short" -> canvas.drawRect(356f, 180f, 360f, 198f, paint)
                    "inset" -> canvas.drawRect(356f, 180f, 359f, 680f, paint)
                    "split" -> { canvas.drawRect(356f, 180f, 360f, 400f, paint); canvas.drawRect(356f, 420f, 360f, 680f, paint) }
                    "neighbor" -> canvas.drawRect(354f, 180f, 358f, 680f, paint)
                }
                val input = input(); input.captureFrom(bitmap)
                assertNull(kind, input.blocks)
                assertNotNull(input.sha256)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun defaultAndUnverifiedBoundaryNeverEnableWechatBodyAdaptation() {
        val bitmap = sample()
        try {
            val generic = input(enabled = false); generic.captureFrom(bitmap)
            assertEquals(360, generic.blocks!!.width)
            val adapted = input(); adapted.captureFrom(bitmap, bodyBoundaryVerified = false)
            assertNull(adapted.blocks)
            for (density in listOf(0f, Float.NaN)) {
                adapted.captureFrom(bitmap, density)
                assertNull(adapted.blocks)
            }
        } finally { bitmap.recycle() }
    }


    @Test fun onlyUniformHeaderSeparatorRowsMayBeExcludedFromBodyBlocks() {
        val density = 3.5f
        val bitmap = sample(density)
        val top = (56 * density).toInt()
        try {
            val input = input(density)
            input.captureFrom(bitmap, density)
            val original = input.blocks!!
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, top, Color.rgb(227, 227, 227))
                bitmap.setPixel(x, top + 1, Color.rgb(235, 235, 235))
            }
            input.captureFrom(bitmap, density)
            assertNotNull(input.blocks)
            assertEquals(original.hashes, input.blocks!!.hashes)
            assertEquals(original.height - 2, input.blocks!!.height)
            val fullHash = input.sha256
            bitmap.setPixel(bitmap.width / 2, top, Color.BLACK)
            input.captureFrom(bitmap, density)
            assertNull("A single body pixel in the separator must remain protected", input.blocks)
            assertNotEquals(fullHash, input.sha256)
            Canvas(bitmap).drawRect(100f, top.toFloat(), 300f, top + 4f, Paint().apply { color = Color.BLUE })
            input.captureFrom(bitmap, density)
            assertNull("A clipped picture must not be trimmed as header chrome", input.blocks)
        } finally { bitmap.recycle() }
    }


    @Test fun onlyUniformVerifiedInputSeparatorRowsMayBeExcludedFromBodyBlocks() {
        val density = 3.5f
        val bitmap = sample(density)
        try {
            val input = input(density)
            input.captureFrom(bitmap, density)
            val original = input.blocks!!
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, bitmap.height - 2, Color.rgb(235, 235, 235))
                bitmap.setPixel(x, bitmap.height - 1, Color.rgb(211, 211, 211))
            }
            input.captureFrom(bitmap, density)
            assertNotNull(input.blocks)
            assertEquals(original.hashes, input.blocks!!.hashes)
            assertEquals(original.height - 2, input.blocks!!.height)
            val fullHash = input.sha256
            input.captureFrom(bitmap, density, bodyBoundaryVerified = false)
            assertNull(input.blocks)
            bitmap.setPixel(bitmap.width / 2, bitmap.height - 1, Color.BLACK)
            input.captureFrom(bitmap, density)
            assertNull("A short message pixel at the bottom border cannot be discarded", input.blocks)
            assertNotEquals(fullHash, input.sha256)
        } finally { bitmap.recycle() }
    }

    @Test fun confirmedWechatListKeepsItsDedicatedHash() {
        val bitmap = wechatListSample()
        try {
            val input = ScreenshotContentInput(44, detectWechatList = true, detectBlocks = true, detectWechatBody = true)
            input.captureFrom(bitmap)
            assertNotNull(input.wechatListSha256)
            assertNull(input.blocks)
        } finally { bitmap.recycle() }
    }
}
