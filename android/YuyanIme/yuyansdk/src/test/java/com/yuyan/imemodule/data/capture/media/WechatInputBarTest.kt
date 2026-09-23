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
class WechatInputBarTest {
    private fun sendSample(
        dark: Boolean = false,
        density: Float = 1f,
        height: Int = 800,
        voice: Boolean = true,
        emoji: Boolean = true,
        label: Boolean = true,
        buttonColor: Int = Color.rgb(7, 193, 96),
        buttonWidth: Float = 56f,
    ): Bitmap = Bitmap.createBitmap((360 * density).toInt(), (height * density).toInt(), Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.scale(density, density)
        canvas.drawColor(if (dark) Color.rgb(20, 20, 20) else Color.rgb(238, 238, 238))
        val top = height - 54f
        val paint = Paint().apply { color = if (dark) Color.rgb(35, 35, 35) else Color.rgb(247, 247, 247) }
        canvas.drawRect(0f, top, 360f, height.toFloat(), paint)
        paint.color = if (dark) Color.WHITE else Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        if (voice) canvas.drawCircle(23f, top + 26, 11f, paint)
        if (emoji) canvas.drawCircle(352f - buttonWidth - 20f, top + 26, 11f, paint)
        paint.style = Paint.Style.FILL
        paint.color = buttonColor
        canvas.drawRoundRect(352f - buttonWidth, top + 10, 352f, top + 42, 4f, 4f, paint)
        if (label) {
            // 两个脱敏字形，不依赖宿主字体或真实聊天图。
            paint.color = Color.WHITE
            canvas.drawRect(352f - buttonWidth / 2 - 12, top + 20, 352f - buttonWidth / 2 - 9, top + 32, paint)
            canvas.drawRect(352f - buttonWidth / 2 + 3, top + 20, 352f - buttonWidth / 2 + 6, top + 32, paint)
        }
    }

    @Test fun narrowSendToolbarFromReportedLayoutStillExcludesDraftChanges() {
        for (density in listOf(3.375f, 3.5f, 3.625f)) for (dark in listOf(false, true)) {
            val image = sendSample(dark = dark, density = density, buttonWidth = 46f)
            try {
                val top = wechatInputBarTop(image, density)
                assertNotNull("density=$density dark=$dark", top)
                val bounds = com.yuyan.imemodule.data.capture.ui.IntRect(0, 0, image.width, top!!)
                val original = exactPixelHash(image, bounds)
                image.setPixel((180 * density).toInt(), (770 * density).toInt(), Color.RED)
                assertEquals(top, wechatInputBarTop(image, density))
                assertEquals(original, exactPixelHash(image, bounds))
                image.setPixel((180 * density).toInt(), top - 1, Color.RED)
                assertNotEquals(original, exactPixelHash(image, bounds))
            } finally { image.recycle() }
        }
    }

    @Test fun sendToolbarIsRecognizedAcrossThemesAndDensities() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 2f, 2.5f, 3f)) {
            val image = sendSample(dark = dark, density = density)
            try { assertEquals("dark=$dark density=$density", (746 * density).toInt(), wechatInputBarTop(image, density)) }
            finally { image.recycle() }
        }
    }

    @Test fun sendButtonWithoutOtherToolbarEvidenceIsNotCropped() {
        val images = listOf(sendSample(voice = false), sendSample(emoji = false),
            sendSample(label = false), sendSample(buttonColor = Color.GRAY))
        try { images.forEach { assertNull(wechatInputBarTop(it, 1f)) } }
        finally { images.forEach { it.recycle() } }
    }

    @Test fun greenMessageBubbleAboveToolbarDoesNotMoveTheBoundary() {
        val image = sendSample()
        try {
            Canvas(image).drawRoundRect(230f, 705f, 352f, 741f, 4f, 4f,
                Paint().apply { color = Color.rgb(7, 193, 96) })
            assertEquals(746, wechatInputBarTop(image, 1f))
        } finally { image.recycle() }
    }

    @Test fun sendToolbarStillRequiresContinuousEdgesAndSingleLineBoundary() {
        for (multiline in listOf(false, true)) {
            val image = sendSample()
            try {
                Canvas(image).drawRect(0f, if (multiline) 680f else 790f, 360f,
                    if (multiline) 746f else 800f,
                    Paint().apply { color = if (multiline) Color.rgb(247, 247, 247) else Color.BLACK })
                assertNull(wechatInputBarTop(image, 1f))
            } finally { image.recycle() }
        }
    }

    @Test fun draftAndCursorChangesKeepExactBodyAndAssetAcrossPlusSendTransitions() = kotlinx.coroutines.runBlocking {
        var source = sample()
        val paths = mutableSetOf<String>()
        val capturer = WindowMediaCapturer(androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            ScreenshotSource { _, _ -> WindowScreenshotResult.Success(source.copy(Bitmap.Config.ARGB_8888, false), 0, 0) })
        suspend fun capture(): Pair<String, String?> {
            val input = ScreenshotContentInput(56)
            val bounds = com.yuyan.imemodule.data.capture.ui.IntRect(0, 0, source.width, source.height)
            val asset = capturer.capture(1, bounds, listOf(MediaCaptureRequest(0, bounds,
                lossyWebp = true, wechatInputBarDensity = 1f, contentInput = input))).getValue(0)
            paths.add(asset.localPath)
            assertEquals(source.height - 54, asset.height)
            assertNotNull(input.sha256)
            return asset.sha256 to input.sha256
        }
        try {
            val empty = capture()
            source.recycle(); source = sendSample()
            assertEquals(empty, capture())
            for (x in listOf(100, 120, 140, 160, 180)) {
                Canvas(source).drawRect(x.toFloat(), 760f, x + 2f, 780f, Paint().apply { color = Color.BLACK })
                assertEquals(empty, capture())
            }
            source.recycle(); source = sample()
            assertEquals(empty, capture())
            source.recycle(); source = sendSample()
            source.setPixel(180, 745, Color.BLACK)
            assertNotEquals(empty.second, capture().second)
            // 键盘弹出缩小可见正文；收起后显露更多正文，均不当作草稿重复。
            source.recycle(); source = sendSample(height = 520)
            val keyboardOpen = capture()
            assertNotEquals(empty.second, keyboardOpen.second)
            source.recycle(); source = sendSample()
            assertNotEquals(keyboardOpen.second, capture().second)
        } finally { source.recycle(); paths.forEach { java.io.File(it).delete() } }
    }

    private fun sample(dark: Boolean = false, plus: Boolean = true): Bitmap =
        Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(if (dark) Color.rgb(20,20,20) else Color.rgb(238,238,238))
            val paint = Paint().apply { color = if (dark) Color.rgb(35,35,35) else Color.rgb(247,247,247) }
            canvas.drawRect(0f,746f,360f,800f,paint)
            paint.color = if (dark) Color.WHITE else Color.BLACK
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            canvas.drawCircle(23f,772f,11f,paint)
            canvas.drawCircle(338f,772f,11f,paint)
            if (plus) {
                canvas.drawLine(333f,772f,343f,772f,paint)
                canvas.drawLine(338f,767f,338f,777f,paint)
            }
        }
    @Test fun capturedHashIgnoresToolbarButKeepsSmallBodyChanges() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = sample()
        val paths = mutableSetOf<String>()
        val capturer = WindowMediaCapturer(context, ScreenshotSource { _, _ ->
            WindowScreenshotResult.Success(source.copy(Bitmap.Config.ARGB_8888, false), 0, 0)
        })
        suspend fun capture(adapt: Boolean = true): com.yuyan.imemodule.data.capture.db.PendingAssetEntity {
            val bounds = com.yuyan.imemodule.data.capture.ui.IntRect(0,0,360,800)
            return capturer.capture(1, bounds, listOf(MediaCaptureRequest(0, bounds,
                wechatInputBarDensity = if (adapt) 1f else null))).getValue(0).also { paths.add(it.localPath) }
        }
        try {
            assertEquals(800, capture(false).height)
            val first = capture()
            assertEquals(746, first.height)
            Canvas(source).drawRect(100f,760f,120f,780f,Paint().apply { color = Color.RED })
            val inputChanged = capture()
            assertEquals(first.sha256, inputChanged.sha256)
            assertEquals(first.perceptualHash, inputChanged.perceptualHash)
            source.setPixel(180, 745, Color.BLACK)
            assertNotEquals(first.sha256, capture().sha256)
        } finally { source.recycle(); paths.forEach { java.io.File(it).delete() } }
    }
    @Test fun locatesVerifiedToolbarWithoutFixedBottomCrop() {
        val image = sample()
        try { assertEquals(746, wechatInputBarTop(image, 1f)) } finally { image.recycle() }
    }
    @Test fun darkToolbarUsesContrastRatherThanBlackTextAssumption() {
        val image = sample(dark = true)
        try { assertEquals(746, wechatInputBarTop(image, 1f)) } finally { image.recycle() }
    }
    @Test fun doesNotCropAnUnverifiedBottomRow() {
        val image = sample(plus = false)
        try { assertNull(wechatInputBarTop(image, 1f)) } finally { image.recycle() }
    }
    @Test fun toolbarLikePictureAwayFromBottomIsNotAnInputBar() {
        val toolbar = sample()
        val image = Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.rgb(238,238,238))
            Canvas(image).drawBitmap(toolbar,0f,-120f,null)
            assertNull(wechatInputBarTop(image,1f))
        } finally { toolbar.recycle(); image.recycle() }
    }
    @Test fun changingEdgeBackgroundRejectsPictureOrOverlayCandidate() {
        val image = sample()
        try {
            Canvas(image).drawRect(0f,790f,360f,800f,Paint().apply { color=Color.BLACK })
            assertNull(wechatInputBarTop(image,1f))
        } finally { image.recycle() }
    }
    @Test fun oversizedMultilineToolbarRemainsUncropped() {
        val image = sample()
        try {
            Canvas(image).drawRect(0f,680f,360f,746f,Paint().apply { color=Color.rgb(247,247,247) })
            assertNull(wechatInputBarTop(image,1f))
        } finally { image.recycle() }
    }
    @Test fun blankScreenshotAndInvalidDensityFailClosed() {
        val image = Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888)
        try { assertNull(wechatInputBarTop(image, 1f)); assertNull(wechatInputBarTop(image, Float.NaN)) } finally { image.recycle() }
    }
}
