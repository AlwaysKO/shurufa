package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.ServerConfig
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PageCaptureSyncTest {
    @Test fun receiptReadIsBoundedAndRejectsInvalidUtf8() {
        assertEquals("{}", "{}".toResponseBody().use { readPageReceipt(it) })
        assertEquals(4096, "x".repeat(4096).toResponseBody().use { readPageReceipt(it) }!!.length)
        assertNull("x".repeat(4097).toResponseBody().use { readPageReceipt(it) })
        assertNull(byteArrayOf(0xc3.toByte(), 0x28).toResponseBody().use { readPageReceipt(it) })
    }
    @Test fun unknownResponseLengthStillCannotExceedLimit() {
        val content = okio.Buffer().writeUtf8("x".repeat(8192))
        val body = object : okhttp3.ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
            override fun source() = content
        }
        assertNull(body.use { readPageReceipt(it) })
    }
    @Test fun switchingTargetAwayAndBackInvalidatesOldEpoch() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ServerConfig.init(app)
        val old = ServerConfig.baseUrl
        try {
            ServerConfig.updateOnlineServerUrl("https://page-a.example.com")
            val epoch = ServerConfig.onlineEpoch
            ServerConfig.updateOnlineServerUrl("https://page-b.example.com")
            ServerConfig.updateOnlineServerUrl("https://page-a.example.com")
            assertEquals("https://page-a.example.com", ServerConfig.baseUrl)
            assertEquals(epoch + 2, ServerConfig.onlineEpoch)
        } finally { ServerConfig.updateOnlineServerUrl(old) }
    }
}
