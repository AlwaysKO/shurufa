package com.yuyan.imemodule.data.capture.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.GameWorkPausedException
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationMediaImporterGameTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = Files.createTempDirectory("deferred-notification").toFile()
    @Before fun idle() { resetGameWorkRuntimeForTest(); ShadowSystemClock.advanceBy(Duration.ofSeconds(4)) }
    @After fun cleanup() { resetGameWorkRuntimeForTest(); directory.deleteRecursively() }

    @Test fun gamePausePropagatesWithoutOpeningOrDecodingSource() = runBlocking {
        val uri = Uri.parse("content://temporary/image")
        var opened = 0
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { opened++; byteArrayOf(1).inputStream() }
        GameWorkRuntime.setGaming(true)
        try { NotificationMediaImporter(context).importImage(uri); fail("game pause must not become null/failed asset") }
        catch (_: GameWorkPausedException) { }
        assertEquals(0, opened)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun resumedTaskNormalizesPrivateOriginalBeforeAnyAssetIsAcknowledged() = runBlocking {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray()
        bitmap.recycle()
        val snapshot = NotificationSnapshot("com.tencent.mm", "notice", "同事", "[图片]", 1000,
            mediaUri = "content://expired/photo", mediaUriReadable = true)
        GameWorkRuntime.setGaming(true)
        val queue = DeferredNotificationMedia(directory)
        assertEquals(DeferredMediaStage.Stored, queue.stage(snapshot) { bytes.inputStream() })
        assertFalse(queue.processNext(ImageUploadRuntime::isBackgroundWorkAllowed) { _, _ -> fail("paused"); true })
        GameWorkRuntime.setGaming(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        val restarted = DeferredNotificationMedia(directory)
        assertTrue(restarted.processNext(ImageUploadRuntime::isBackgroundWorkAllowed) { _, original ->
            val uri = Uri.fromFile(original)
            assertArrayEquals("private file URI must open original encoded bytes", bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            val decoded = android.graphics.BitmapFactory.decodeFile(original.absolutePath)
            assertNotNull("JPEG test fixture must decode", decoded)
            decoded?.recycle()
            val asset = NotificationMediaImporter(context).importImage(uri)
            assertNotNull("normalizing a readable staged JPEG must yield an asset", asset)
            requireNotNull(asset)
            assertEquals("image/png", asset.mimeType)
            assertNotEquals(original.absolutePath, asset.localPath)
            assertTrue(File(asset.localPath).readBytes().take(4).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71)))
            assertArrayEquals(bytes, original.readBytes())
            true
        })
        assertTrue(restarted.pending().isEmpty())
    }
}
