package com.yuyan.imemodule.data.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ChatCaptureMetadataCallTest {
    @Before @After fun reset() { resetImageInputForTest(); resetGameWorkRuntimeForTest() }
    @Test fun configGetIsTrackedAndTypingCancelsInFlightCallWithoutWifiRequirement() = runBlocking {
        val call = ImageUploadRuntime.prepareBackgroundCall(OkHttpClient(), Request.Builder().url("https://example.test/config").build(), { true })
        assertNotNull(call)
        ImageUploadRuntime.noteKeyActivity()
        withTimeout(3000) { while (!call!!.isCanceled()) delay(10) }
        ImageUploadRuntime.finishChatCall(call!!)
    }
    @Test fun revokedConsentCannotStartConfigurationGet() {
        assertNull(ImageUploadRuntime.prepareBackgroundCall(OkHttpClient(), Request.Builder().url("https://example.test/config").build(), { false }))
    }
}
