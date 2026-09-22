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
