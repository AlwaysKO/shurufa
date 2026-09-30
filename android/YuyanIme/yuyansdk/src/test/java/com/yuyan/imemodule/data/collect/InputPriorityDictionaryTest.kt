package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InputPriorityDictionaryTest {
    @Test fun `注册请求等待期间恢复打字不能继续导出并结算词库`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "idle-dictionary-${UUID.randomUUID()}.db"
        var now = 1000L
        val idle = AtomicBoolean(true)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                idle.set(false)
                return MockResponse().setBody("{\"ok\":true,\"has_report\":true}")
            }
        }
        server.start()
        try {
            val store = LocalInputStore(context, name, { now })
            try {
                store.stageLearning("pending", listOf(PendingChoice("6243", "那个", "na ge")), emptyList())
                now += 20_000L
                val sync = PersonalDictionarySync(store, context.getSharedPreferences(name, 0), OkHttpClient(),
                    "device", server.url("/").toString(), { idle.get() }, { "complete" to 0 }, restoreFromTarget = false)
                assertFalse(sync.run())
                // dictionaryExport 会 settleLearning；保留临时奖励可证明整个导出没有开始。
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_learning", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM learned_input", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                assertEquals(1, server.requestCount)
            } finally { store.close() }
        } finally { server.shutdown(); context.deleteDatabase(name) }
    }
}
