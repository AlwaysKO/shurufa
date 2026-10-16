package com.yuyan.imemodule.data.capture.page

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.capture.media.*
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocalPageFrameReaderWaitTest {
    class Service : AccessibilityService() {
        var screenshots = 0
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
        override fun takeScreenshot(displayId: Int, executor: java.util.concurrent.Executor, callback: TakeScreenshotCallback) {
            screenshots++
            error("stale payment must not request a screenshot")
        }
    }
    @Test fun leavingPageWhileWaitingRejectsBeforeScreenshotOrPersistence() = checkGuard("scope_lost")
    @Test fun revokingConsentWhileWaitingRejectsBeforeScreenshotOrPersistence() = checkGuard("authorization_lost")
    @Test fun inputStartingWhileWaitingRejectsBeforeScreenshotOrPersistence() = checkGuard("background_paused")
    private fun checkGuard(reason: String) = runBlocking {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get()
        val app = service.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).commit()
        GameWorkRuntime.setGaming(false)
        ImageUploadRuntime.noteKeyActivity()
        android.os.SystemClock.sleep(3001)
        ShadowLog.clear()
        val media = WindowMediaCapturer(app, ScreenshotSource { _, _ -> error("unexpected chat request") })
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { media.tryWithPageCaptureSlot { release.await() } }
        var current = true
        val reader = LocalPageFrameReader(service, media)
        PageCaptureOutbox(app).use { outbox ->
            val payment = async(start = CoroutineStart.UNDISPATCHED) {
                reader.readAndPersist(outbox,"com.tencent.mm",1,IntRect(0,0,100,200),emptyList(),
                    current = { current }, waitForSlotMillis = 2000)
            }
            assertFalse("must actually wait before changing guard", payment.isCompleted)
            when (reason) {
                "scope_lost" -> current = false
                "authorization_lost" -> prefs.edit().putBoolean(CollectionConsent.KEY, false).commit()
                else -> ImageUploadRuntime.noteKeyActivity()
            }
            release.complete(Unit); owner.join()
            payment.await()
            assertEquals(0,service.screenshots)
            assertTrue(outbox.pending().isEmpty())
            assertTrue(ShadowLog.getLogsForTag("BrowsingPageProbe").any { it.msg == reason })
            assertEquals("free",media.tryWithPageCaptureSlot { "free" })
        }
        controller.destroy()
        Unit
    }
}
