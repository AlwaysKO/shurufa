package com.yuyan.imemodule.service.capture

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.service.notification.StatusBarNotification
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.CaptureCoordinator
import com.yuyan.imemodule.data.capture.CaptureOutboxStore
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.notification.*
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationMediaRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val listener = Robolectric.buildService(PassiveNotificationListener::class.java).get()
    private val directory = Files.createTempDirectory("notification-recovery").toFile()
    private val persisted = mutableListOf<List<PendingAssetEntity>>()
    private fun field(name: String) = listener.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Before fun setup() {
        resetGameWorkRuntimeForTest()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(CollectionConsent.KEY, true).commit()
        field("mediaImporter").set(listener, NotificationMediaImporter(context))
        field("coordinator").set(listener, CaptureCoordinator(store = object : CaptureOutboxStore {
            override suspend fun enqueueIfNew(seenMessage: SeenMessageEntity, pendingMessage: PendingMessageEntity,
                pendingAssets: List<PendingAssetEntity>): Boolean { persisted += pendingAssets; return true }
        }, deviceId = { "device" }, wakeUploader = {}))
    }
    @After fun cleanup() {
        (field("scope").get(listener) as CoroutineScope).cancel()
        (field("mediaDispatcher").get(listener) as ExecutorCoroutineDispatcher).close()
        resetGameWorkRuntimeForTest()
        directory.deleteRecursively()
    }
    private suspend fun replay(): Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
        listener.javaClass.getDeclaredMethod("processDeferredMedia", Continuation::class.java)
            .apply { isAccessible = true }.invoke(listener, continuation)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun temporaryImporterFailureRetainsOriginalAndLaterReplayPersistsOnlyPng() = runBlocking {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        val queue = DeferredNotificationMedia(directory)
        field("deferredMedia").set(listener, queue)
        queue.stage(NotificationSnapshot("com.tencent.mm", "notice", "同事", "[图片]", 1000,
            mediaUri = "content://expired/image", mediaUriReadable = true)) { bytes.inputStream() }
        val original = queue.pending().single().file
        var readable = false
        shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.fromFile(original)) {
            if (!readable) throw IOException("temporarily unavailable")
            original.inputStream()
        }
        assertFalse("failed normalization must not acknowledge the only original", replay())
        assertArrayEquals(bytes, original.readBytes())
        assertTrue(persisted.isEmpty())
        readable = true
        assertTrue(replay())
        val asset = persisted.single().single()
        assertEquals("image/png", asset.mimeType)
        assertNotEquals(original.absolutePath, asset.localPath)
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun mediaAdmissionIsBoundedBeforeLaunchingAndCancellationReleasesCapacity() {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        fun replaceScope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher).also { field("scope").set(listener, it) }
        var pendingScope = replaceScope()
        fun post(id: Int) {
            val notification = Notification().apply {
                extras.putCharSequence(Notification.EXTRA_TITLE, "同事")
                extras.putCharSequence(Notification.EXTRA_TEXT, "[图片]")
                extras.putParcelable(Notification.EXTRA_AUDIO_CONTENTS_URI, Uri.parse("content://image/$id"))
            }
            listener.onNotificationPosted(StatusBarNotification("com.tencent.mm", "com.tencent.mm", id, null,
                0, 0, 0, notification, android.os.Process.myUserHandle(), 1000L + id))
        }
        repeat(33, ::post)
        assertEquals("admission must happen before scope.launch", 32, pendingScope.coroutineContext[Job]!!.children.count())
        assertEquals("admission_full", context.getSharedPreferences("notification-media-status", Context.MODE_PRIVATE).getString("last_reason", null))
        val dedup = field("eventDeduplicator").get(listener) as NotificationEventDeduplicator
        assertTrue(dedup.shouldAccept(NotificationSnapshot("com.tencent.mm", "0|com.tencent.mm|32|null|0", "同事", "[图片]", 1032,
            mediaUri = "content://image/32", mediaUriReadable = true)))
        pendingScope.cancel()
        while (queued.isNotEmpty()) queued.removeFirst().run()
        pendingScope = replaceScope()
        repeat(32, ::post)
        assertEquals("cancelled work must release all admission slots", 32, pendingScope.coroutineContext[Job]!!.children.count())
        pendingScope.cancel()
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }
}
