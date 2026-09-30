package com.yuyan.imemodule.data.navigation

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.GuardedChatBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NavigationUploadGateTest {
    @Test fun closingAndReopeningDuringUploadDoesNotResumeOldTransfer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CollectionConsent.setEnabled(context, true)
        for (reopen in listOf(false, true)) {
            NavigationSettings.setEnabled(context, true)
            val version = NavigationSettings.generation.get()
            var chunks = 0
            val body = GuardedChatBody(ByteArray(40_000).toRequestBody(), allowed = { NavigationSettings.uploadAllowed(context, version) }, pause = {
                if (++chunks == 2) {
                    NavigationSettings.setEnabled(context, false)
                    if (reopen) NavigationSettings.setEnabled(context, true)
                }
            })
            val sink = Buffer()
            try { body.writeTo(sink); fail("关闭后不能上传完整图片") } catch (_: IOException) { }
            assertEquals(8192L, sink.size)
        }
    }
}
