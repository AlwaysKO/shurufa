package com.yuyan.imemodule.data.collect
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PersonalDictionarySyncTest {
    private inline fun <T> LocalInputStore.withStore(block: (LocalInputStore) -> T): T = try { block(this) } finally { close() }
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val json=Json { encodeDefaults=true }
    private fun body(entries:List<DictionaryRecord> = emptyList())=json.encodeToString(DictionarySnapshot.serializer(),DictionarySnapshot("group","b".repeat(64),entries,emptyList()))
    @Test fun `先上报本机后事务恢复最后确认且关闭开关不联网`() = runBlocking {
        val server=MockWebServer();server.start()
        LocalInputStore(context,"sync-${UUID.randomUUID()}.db").withStore { store ->
            val prefs=context.getSharedPreferences("test-${UUID.randomUUID()}",0)
            var enabled=false
            val sync=PersonalDictionarySync(store,prefs,OkHttpClient(),"new",server.url("/").toString(),{enabled},{"complete" to 0})
            assertFalse(sync.run());assertEquals(0,server.requestCount)
            store.learn("2466434262","充电宝",pinyin="chong dian bao")
            enabled=true
            server.enqueue(MockResponse().setBody("{\"ok\":true}"));server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setBody(body(listOf(DictionaryRecord("choice","怎么","93663","","selection",3,3.0,1000,"old")))))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(sync.run())
            val register=server.takeRequest();assertTrue(register.path!!.endsWith("/register"));assertEquals(64,register.getHeader("X-Dictionary-Token")!!.length)
            val report=server.takeRequest();assertTrue(report.path!!.endsWith("/report"));assertTrue(report.body.readUtf8().contains("充电宝"))
            assertEquals("/api/v1/mobile/dictionary",server.takeRequest().path)
            assertTrue(server.takeRequest().path!!.endsWith("/ack"))
            assertEquals(3L,store.learned("93663").single().count)
            assertFalse(store.dictionaryExport().any { it.text=="怎么" })
        }
        server.shutdown()
    }
    @Test fun `下载坏数据不确认不清空本机词库`() = runBlocking {
        val server=MockWebServer();server.start()
        LocalInputStore(context,"sync-${UUID.randomUUID()}.db").withStore { store ->
            store.learn("93663","怎么")
            val sync=PersonalDictionarySync(store,context.getSharedPreferences("test-${UUID.randomUUID()}",0),OkHttpClient(),"new",server.url("/").toString(),{true},{"unavailable" to 0})
            server.enqueue(MockResponse().setBody("{\"ok\":true}"));server.enqueue(MockResponse().setBody("{\"ok\":true}"));server.enqueue(MockResponse().setBody("{\"entries\":[]}"))
            assertFalse(sync.run());assertEquals(3,server.requestCount);assertEquals(1L,store.learned("93663").single().count)
        };server.shutdown()
    }
}
