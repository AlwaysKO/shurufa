package com.yuyan.imemodule.data.capture.media
import android.graphics.Bitmap
import android.content.ContextWrapper
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.collect.resetImageInputForTest
import com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class MediaPreparationDiagnosticsTest {
    @Before @After fun reset() { resetImageInputForTest(); resetGameWorkRuntimeForTest() }
    @Test fun invalidCropAndUnwritableCacheReplaceReadyWithOneSafeFailure() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val cacheFile = File(app.cacheDir, "blocked-cache").apply { writeText("synthetic") }
        try {
            for (fileFailure in listOf(false, true)) {
                val statuses = mutableListOf<Pair<String, Int?>>()
                val context = if (fileFailure) object : ContextWrapper(app) { override fun getCacheDir() = cacheFile } else app
                val media = WindowMediaCapturer(context, ScreenshotSource { _, _ ->
                    WindowScreenshotResult.Success(Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888),0,0)
                }, onScreenshotResult = { _, status, code -> statuses += status to code })
                val bounds = IntRect(0,0,100,100)
                val requested = if (fileFailure) bounds else IntRect(200,200,300,300)
                val result = runCatching { media.capture(1,bounds,listOf(MediaCaptureRequest(0,requested,platform=ChatPlatform.WECHAT))) }.getOrDefault(emptyMap())
                assertTrue(result.isEmpty())
                assertEquals(listOf("failed" to -1004), statuses)
            }
        } finally { cacheFile.delete() }
    }
    @Test fun encodingFailureIsSafeEnumeratedAndCannotLeaveReady() = runBlocking {
        val statuses = mutableListOf<Pair<String,Int?>>()
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            WindowScreenshotResult.Success(Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888),0,0)
        }, onScreenshotResult={ _,status,code -> statuses += status to code }, encodeAsset={ _,_ -> throw IllegalStateException("not recorded") })
        val bounds=IntRect(0,0,100,100)
        val result=runCatching { media.capture(1,bounds,listOf(MediaCaptureRequest(0,bounds,platform=ChatPlatform.DOUYIN))) }.getOrDefault(emptyMap())
        assertTrue(result.isEmpty())
        assertEquals(listOf("failed" to -1004),statuses)
    }
    @Test fun coordinatorMissingAssetEmitsFailureWithoutInventingPersistOrUpload() = runBlocking {
        val statuses = mutableListOf<Pair<String,Int?>>()
        var persisted = false
        var woke = false
        val root = javaClass.getResourceAsStream("/capture/douyin-chat-40.6.0.json")!!.bufferedReader().use {
            kotlinx.serialization.json.Json.decodeFromString<com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot>(it.readText())
        }
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            WindowScreenshotResult.Success(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888),0,0)
        }, onScreenshotResult={ _,status,code -> statuses += status to code })
        val store = object : com.yuyan.imemodule.data.capture.CaptureOutboxStore {
            override suspend fun enqueueIfNew(seenMessage: com.yuyan.imemodule.data.capture.db.SeenMessageEntity,
                pendingMessage: com.yuyan.imemodule.data.capture.db.PendingMessageEntity,
                pendingAssets: List<com.yuyan.imemodule.data.capture.db.PendingAssetEntity>): Boolean {
                fail("missing screenshot must not reach outbox"); return false
            }
        }
        val coordinator = com.yuyan.imemodule.data.capture.CaptureCoordinator(store=store, deviceId={ "synthetic" },
            wakeUploader={ woke=true }, mediaCapturer=media, onPersistResult={ _,_ -> persisted=true })
        assertTrue(coordinator.capture("com.ss.android.ugc.aweme", root, 1))
        assertEquals(listOf("failed" to -1004), statuses)
        assertFalse(persisted); assertFalse(woke)
    }

}
