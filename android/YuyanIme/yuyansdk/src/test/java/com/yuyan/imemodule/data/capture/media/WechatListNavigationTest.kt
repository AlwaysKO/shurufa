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
class WechatListNavigationTest {

    @Test fun navigationChangesDoNotChangeListHashButEveryListPixelStillCounts() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 2f, 3f, 3.25f)) {
            val bitmap = wechatListSample(dark, density)
            val input = ScreenshotContentInput((44 * density).toInt(), detectWechatList = true)
            try {
                assertEquals((744 * density).toInt(), wechatListNavigationTop(bitmap, density))
                input.captureFrom(bitmap, density)
                val listHash = input.wechatListSha256
                val fullHash = input.sha256
                assertNotNull(listHash)
                Canvas(bitmap).drawCircle(264 * density, 752 * density, 3 * density, Paint().apply { color = Color.RED })
                input.captureFrom(bitmap, density)
                assertEquals(listHash, input.wechatListSha256)
                assertNotEquals(fullHash, input.sha256)
                // 分别代表头像未读点、预览、时间，以及紧贴导航的最后一行。
                for ((x, y) in listOf(50 to 70, 150 to 100, 360 to 65, 180 to 743)) {
                    val px = (x * density).toInt(); val py = (y * density).toInt()
                    val original = bitmap.getPixel(px, py)
                    bitmap.setPixel(px, py, Color.RED)
                    input.captureFrom(bitmap, density)
                    assertNotEquals("$x,$y density=$density", listHash, input.wechatListSha256)
                    bitmap.setPixel(px, py, original)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun missingTabsAndOverlaysDoNotSupplyAListDedupKey() {
        for (tabs in listOf(0, 1, 3)) {
            val bitmap = wechatListSample(tabs = tabs)
            try { assertNull(wechatListNavigationTop(bitmap, 1f)) } finally { bitmap.recycle() }
        }
        val bitmap = wechatListSample()
        try {
            Canvas(bitmap).drawRect(0f, 792f, 400f, 800f, Paint().apply { color = Color.BLACK })
            assertNull(wechatListNavigationTop(bitmap, 1f))
        } finally { bitmap.recycle() }
    }
}

internal fun wechatListSample(dark: Boolean = false, density: Float = 1f, tabs: Int = 4): Bitmap =
        Bitmap.createBitmap((400 * density).toInt(), (800 * density).toInt(), Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this).apply { scale(density, density) }
            canvas.drawColor(if (dark) Color.rgb(24, 24, 24) else Color.WHITE)
            val paint = Paint().apply { color = if (dark) Color.rgb(30, 30, 30) else Color.rgb(247, 247, 247) }
            canvas.drawRect(0f, 744f, 400f, 800f, paint)
            repeat(tabs) { i ->
                val cx = 50f + 100 * i
                paint.color = if (i == 0) Color.rgb(7, 193, 96) else if (dark) Color.LTGRAY else Color.DKGRAY
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                canvas.drawCircle(cx, 763f, 10f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawRect(cx - 5, 780f, cx - 2, 791f, paint)
                canvas.drawRect(cx + 2, 780f, cx + 5, 791f, paint)
            }
        }

