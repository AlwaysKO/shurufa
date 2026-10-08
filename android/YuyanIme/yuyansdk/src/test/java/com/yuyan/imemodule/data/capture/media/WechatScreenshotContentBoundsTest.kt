package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.yuyan.imemodule.data.capture.ScreenshotContentReason
import com.yuyan.imemodule.data.capture.RecentScreenshotContents
import com.yuyan.imemodule.data.capture.ScreenshotContentEvidence
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
                assertEquals(ScreenshotContentReason.READY, input.reason)
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
                assertEquals(kind, ScreenshotContentReason.SCROLLBAR_UNVERIFIED, input.reason)
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
            assertEquals(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED, adapted.reason)
            for (density in listOf(0f, Float.NaN)) {
                adapted.captureFrom(bitmap, density)
                assertNull(adapted.blocks)
                assertEquals(ScreenshotContentReason.INVALID_BOUNDS, adapted.reason)
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

    @Test fun verifiedHonorSizedDarkBodyWithBottomReachingBarKeepsTheSameBlocks() {
        val bitmap = Bitmap.createBitmap(1200, 1487, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(17, 17, 17))
            val canvas = Canvas(bitmap)
            canvas.drawRect(36f, 304f, 465f, 424f, Paint().apply { color = Color.rgb(45, 45, 45) })
            canvas.drawRect(880f, 1330f, 1164f, 1450f, Paint().apply { color = Color.rgb(50, 180, 110) })
            canvas.drawRect(0f, 1486f, 1200f, 1487f, Paint().apply { color = Color.rgb(40, 40, 40) })
            val input = ScreenshotContentInput(182, detectBlocks = true, detectWechatBody = true)
            input.captureFrom(bitmap, 3.25f)
            val original = input.blocks!!
            canvas.drawRect(1188f, 656f, 1200f, 1486f, Paint().apply { color = Color.rgb(104, 104, 104) })
            input.captureFrom(bitmap, 3.25f)
            assertEquals(ScreenshotContentReason.READY, input.reason)
            assertEquals(original.hashes, input.blocks!!.hashes)
            assertArrayEquals(original.rowHashes, input.blocks!!.rowHashes)
            // 已验证全宽分隔线上任何非均匀像素仍拒绝，不能把压缩噪声假定为原始截图。
            bitmap.setPixel(1199, 1486, Color.rgb(38, 38, 38))
            input.captureFrom(bitmap, 3.25f)
            assertNull(input.blocks)
            assertEquals(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED, input.reason)
        } finally { bitmap.recycle() }
    }

    private fun shadowedTitleBody(shadowOffset: Int = 1, shade: Int = Color.rgb(16, 16, 16)): Bitmap =
        Bitmap.createBitmap(1200, 1487, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(17, 17, 17))
            val canvas = Canvas(this)
            val ink = Paint().apply { color = Color.rgb(70, 70, 70) }
            canvas.drawRect(100f, 143f, 220f, 170f, ink)
            canvas.drawRect(100f, 240f, 450f, 300f, ink)
            canvas.drawRect(600f, 400f, 1000f, 460f, ink)
            val y = 143 + shadowOffset
            for (x in 0 until width) setPixel(x, y, if (x in 100 until 220 && y < 170) ink.color else shade)
        }

    @Test fun verifiedFirstDpNeutralShadowRetainsEveryTextPixelAndEveryRow() {
        for (offset in listOf(0, 1, 3)) {
            val bitmap = shadowedTitleBody(shadowOffset = offset)
            try {
                val input = ScreenshotContentInput(143, detectBlocks = true, detectWechatBody = true)
                input.captureFrom(bitmap, 3.25f)
                assertEquals("首部第 $offset 行", ScreenshotContentReason.READY, input.reason)
                val original = requireNotNull(input.blocks)
                assertEquals(1487 - 143, original.height)
                assertTrue(original.hasClippedEdges)
                val cache = RecentScreenshotContents()
                cache.record(ScreenshotContentEvidence("peer", "title", original))
                bitmap.setPixel(150, 143 + offset, Color.rgb(71, 71, 71))
                input.captureFrom(bitmap, 3.25f)
                assertEquals(ScreenshotContentReason.READY, input.reason)
                val changed = requireNotNull(input.blocks)
                assertEquals(original.height, changed.height)
                assertEquals(original.hashes, changed.hashes)
                assertFalse(original.rowHashes.contentEquals(changed.rowHashes))
                assertFalse("阴影行内新增单像素必须保留，不能只比较下面完整块", cache.contains(
                    ScreenshotContentEvidence("peer", "title", changed)))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun verifiedTopShadowCanCoexistWithAValidatedRightScrollbar() {
        val bitmap = shadowedTitleBody()
        try {
            val input = ScreenshotContentInput(143, detectBlocks = true, detectWechatBody = true)
            input.captureFrom(bitmap, 3.25f)
            val withoutBar = requireNotNull(input.blocks)
            Canvas(bitmap).drawRect(1188f, 143f, 1200f, 900f,
                Paint().apply { color = Color.rgb(104, 104, 104) })
            input.captureFrom(bitmap, 3.25f)
            assertEquals(ScreenshotContentReason.READY, input.reason)
            assertEquals(withoutBar.hashes, input.blocks!!.hashes)
            assertArrayEquals(withoutBar.rowHashes, input.blocks!!.rowHashes)
        } finally { bitmap.recycle() }
    }

    @Test fun unknownOrUnverifiedTitleShadingNeverRelaxesWholeBodyEdges() {
        for (kind in listOf("outside-first-dp", "strong", "colored", "asymmetric", "unverified")) {
            val bitmap = shadowedTitleBody(
                shadowOffset = if (kind == "outside-first-dp") 4 else 1,
                shade = when (kind) {
                    "strong" -> Color.rgb(15, 15, 15)
                    "colored" -> Color.rgb(16, 16, 17)
                    else -> Color.rgb(16, 16, 16)
                })
            try {
                if (kind == "asymmetric") bitmap.setPixel(0, 144, Color.rgb(17, 17, 17))
                val input = ScreenshotContentInput(143, detectBlocks = true, detectWechatBody = true)
                input.captureFrom(bitmap, 3.25f, bodyBoundaryVerified = kind != "unverified")
                assertNull(kind, input.blocks)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun extractionReasonsResetBetweenFramesAndNeverEnableUncertainContent() {
        val bitmap = sample()
        try {
            val input = input()
            input.captureFrom(bitmap, bodyBoundaryVerified = false)
            assertEquals(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED, input.reason)
            input.captureFrom(bitmap)
            assertEquals(ScreenshotContentReason.READY, input.reason)
            bitmap.eraseColor(Color.BLUE)
            input.captureFrom(bitmap)
            assertNull(input.blocks)
            assertEquals(ScreenshotContentReason.BACKGROUND_UNVERIFIED, input.reason)
            bitmap.eraseColor(Color.rgb(238, 238, 238))
            input.captureFrom(bitmap)
            assertNull(input.blocks)
            assertEquals(ScreenshotContentReason.NO_BLOCKS, input.reason)
            bitmap.setPixel(0, 100, Color.BLACK)
            input.captureFrom(bitmap)
            assertNull(input.blocks)
            assertEquals(ScreenshotContentReason.EDGE_CONTENT_UNVERIFIED, input.reason)
        } finally { bitmap.recycle() }
    }

    @Test fun confirmedWechatListKeepsItsDedicatedHash() {
        val bitmap = wechatListSample()
        try {
            val input = ScreenshotContentInput(44, detectWechatList = true, detectBlocks = true, detectWechatBody = true)
            input.captureFrom(bitmap)
            assertNotNull(input.wechatListSha256)
            assertNull(input.blocks)
            assertEquals(ScreenshotContentReason.CONVERSATION_LIST, input.reason)
        } finally { bitmap.recycle() }
    }
}
