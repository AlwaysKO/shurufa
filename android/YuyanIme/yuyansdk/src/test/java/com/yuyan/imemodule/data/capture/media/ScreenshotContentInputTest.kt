package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotContentInputTest {
    @Test fun mediaPipelineCapturesRawContentBeforeEncodingWithoutChangingAsset() = kotlinx.coroutines.runBlocking {
        val bitmap=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val capturer=WindowMediaCapturer(androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            ScreenshotSource { _,_ -> WindowScreenshotResult.Success(bitmap.copy(Bitmap.Config.ARGB_8888,false),0,0) })
        val paths=mutableSetOf<String>()
        suspend fun capture(): Pair<String,String?> {
            val input=ScreenshotContentInput(20); val bounds=IntRect(0,0,80,100)
            val asset=capturer.capture(1,bounds,listOf(MediaCaptureRequest(0,bounds,lossyWebp=true,contentInput=input))).getValue(0)
            paths.add(asset.localPath)
            return asset.sha256 to input.sha256
        }
        try {
            val before=capture(); assertNotNull(before.second)
            bitmap.setPixel(5,5,Color.BLACK)
            val badge=capture(); assertNotEquals(before.first,badge.first); assertEquals(before.second,badge.second)
            bitmap.setPixel(40,99,Color.BLACK)
            assertNotEquals(before.second,capture().second)
        } finally { bitmap.recycle(); paths.forEach { java.io.File(it).delete() } }
    }

    @Test fun titleChromeDoesNotChangeExactBodyButOneBodyPixelDoes() {
        val bitmap=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val input=ScreenshotContentInput(20)
            input.captureFrom(bitmap); val initial=input.sha256; assertNotNull(initial)
            bitmap.setPixel(5,5,Color.BLACK)
            input.captureFrom(bitmap); assertEquals(initial,input.sha256)
            bitmap.setPixel(40,99,Color.rgb(254,255,255))
            input.captureFrom(bitmap); assertNotEquals(initial,input.sha256)
        } finally { bitmap.recycle() }
    }
    @Test fun actualTitleBoxIncludesEveryTitlePixelButNotUnreadBadge() {
        val bitmap=Bitmap.createBitmap(80,20,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val title=IntRect(25,4,60,17)
        try {
            val initial=exactPixelHash(bitmap,title); assertNotNull(initial)
            bitmap.setPixel(5,5,Color.BLACK); assertEquals(initial,exactPixelHash(bitmap,title))
            bitmap.setPixel(25,4,Color.BLACK); assertNotEquals(initial,exactPixelHash(bitmap,title))
        } finally { bitmap.recycle() }
    }
    @Test fun unknownOrInvalidRegionDoesNotSupplyADedupKey() {
        val bitmap=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888)
        try {
            assertNotEquals(exactPixelHash(bitmap,IntRect(0,0,10,20)),exactPixelHash(bitmap,IntRect(0,0,20,10)))
            for(rect in listOf(IntRect(-1,0,80,100),IntRect(0,0,81,100),IntRect(0,100,80,100))) assertNull(exactPixelHash(bitmap,rect))
            val input=ScreenshotContentInput(100); input.captureFrom(bitmap); assertNull(input.sha256)
        } finally { bitmap.recycle() }
    }
}
