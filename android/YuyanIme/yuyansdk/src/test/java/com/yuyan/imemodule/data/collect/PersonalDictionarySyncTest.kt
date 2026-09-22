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
    @Test fun `习惯页落盘后才确认失败重放不重复次数游标独立`() {
        val server=MockWebServer();server.start()
        try {
            LocalInputStore(context,"habit-sync-${UUID.randomUUID()}.db").withStore { store ->
                val prefs=context.getSharedPreferences("test-${UUID.randomUUID()}",0)
                val sync=PersonalDictionarySync(store,prefs,OkHttpClient(),"new",server.url("/").toString(),{true},{"complete" to 0},restoreFromTarget=false)
                val legacyTarget=server.url("/").toString().trimEnd('/')+"/api/v1/mobile/dictionary#new"
                store.saveDictionaryHabitCursor(legacyTarget,99)
                val record=DictionaryRecord("choice","的","3","","selection",3,3.0,1000,"old",5)
                fun queue(ack:Int) {
                    server.enqueue(MockResponse().setBody("{\"ok\":true,\"has_report\":true,\"habits_supported\":true}"))
                    if(server.requestCount==0) server.enqueue(MockResponse().setBody("{\"ok\":true}"))
                    server.enqueue(MockResponse().setBody(json.encodeToString(DictionaryHabits.serializer(),DictionaryHabits(listOf(DictionaryHabit(7,record)),7,false))))
                    server.enqueue(MockResponse().setResponseCode(ack).setBody("{\"ok\":true}"))
                }
                queue(503);assertFalse(sync.run())
                assertEquals(3L,store.learned("3").single().count)
                assertEquals(99L,store.dictionaryHabitCursor(legacyTarget))
                assertEquals(0L,store.dictionaryHabitCursor(legacyTarget+"#short-code-v1"))
                queue(200);assertTrue(sync.run())
                assertEquals(3L,store.learned("3").single().count)
                assertTrue(store.dictionaryExport().isEmpty())
                assertEquals(7L,store.dictionaryHabitCursor(legacyTarget+"#short-code-v1"))
                val paths=(1..server.requestCount).map {server.takeRequest().path}
                assertEquals(2,paths.count {it=="/api/v1/mobile/dictionary/habits?after=0"})
                assertEquals(2,paths.count {it=="/api/v1/mobile/dictionary/habits/ack"})
            }
        } finally {server.shutdown()}
    }
    @Test fun `坏习惯页和越界游标不确认`() {
        val server=MockWebServer();server.start()
        try {
            LocalInputStore(context,"habit-invalid-${UUID.randomUUID()}.db").withStore {store ->
                val sync=PersonalDictionarySync(store,context.getSharedPreferences("test-${UUID.randomUUID()}",0),OkHttpClient(),"new",server.url("/").toString(),{true},{"complete" to 0},restoreFromTarget=false)
                server.enqueue(MockResponse().setBody("{\"ok\":true,\"habits_supported\":true}"))
                server.enqueue(MockResponse().setBody("{\"ok\":true}"))
                server.enqueue(MockResponse().setBody("{\"entries\":[],\"cursor\":5,\"has_more\":false}"))
                assertFalse(sync.run());assertEquals(3,server.requestCount)
                assertTrue(store.dictionaryExport().isEmpty())
            }
        } finally {server.shutdown()}
    }
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
    @Test fun `电脑镜像只上报不拉取或覆盖主后台决策`() {
        val server=MockWebServer();server.start()
        try {
            LocalInputStore(context,"mirror-${UUID.randomUUID()}.db").withStore { store ->
                store.learn("93663","怎么")
                val prefs=context.getSharedPreferences("test-${UUID.randomUUID()}",0)
                val sync=PersonalDictionarySync(store,prefs,OkHttpClient(),"new",server.url("/").toString(),{true},{"complete" to 0}, restoreFromTarget=false, statePrefix="local_")
                server.enqueue(MockResponse().setBody("{\"ok\":true}"))
                server.enqueue(MockResponse().setBody("{\"ok\":true}"))
                assertTrue(sync.run())
                assertEquals(2,server.requestCount)
                assertEquals("/api/v1/mobile/dictionary/register",server.takeRequest().path)
                assertEquals("/api/v1/mobile/dictionary/report",server.takeRequest().path)
                assertEquals(1L,store.learned("93663").single().count)
            }
        } finally {server.shutdown()}
    }
    @Test fun `两端缓存独立本地失败后补传不重置线上凭据与已确认上传`() {
        val local=MockWebServer();val online=MockWebServer();local.start();online.start()
        try {
            LocalInputStore(context,"dual-${UUID.randomUUID()}.db").withStore { store ->
                store.learn("93663","怎么")
                val prefs=context.getSharedPreferences("test-${UUID.randomUUID()}",0)
                val oldToken="a".repeat(64);prefs.edit().putString("token",oldToken).commit()
                val a=PersonalDictionarySync(store,prefs,OkHttpClient(),"new",online.url("/").toString(),{true},{"complete" to 0})
                val b=PersonalDictionarySync(store,prefs,OkHttpClient(),"new",local.url("/").toString(),{true},{"complete" to 0}, restoreFromTarget=false, statePrefix="local_")
                local.enqueue(MockResponse().setResponseCode(503));assertFalse(b.run())
                online.enqueue(MockResponse().setBody("{\"ok\":true,\"has_report\":false}"))
                online.enqueue(MockResponse().setBody("{\"ok\":true}"));online.enqueue(MockResponse().setBody(body()));online.enqueue(MockResponse().setBody("{\"ok\":true}"))
                assertTrue(a.run());assertEquals(oldToken,online.takeRequest().getHeader("X-Dictionary-Token"))
                repeat(3) {online.takeRequest()}
                local.enqueue(MockResponse().setBody("{\"ok\":true,\"has_report\":false}"));local.enqueue(MockResponse().setBody("{\"ok\":true}"));assertTrue(b.run())
                online.enqueue(MockResponse().setBody("{\"ok\":true,\"has_report\":true}"));online.enqueue(MockResponse().setBody(body()));online.enqueue(MockResponse().setBody("{\"ok\":true}"));assertTrue(a.run())
                assertEquals("/api/v1/mobile/dictionary/register",online.takeRequest().path)
                assertEquals("/api/v1/mobile/dictionary",online.takeRequest().path) // 没有重复report
                assertEquals("/api/v1/mobile/dictionary/ack",online.takeRequest().path)
                assertEquals(3,local.requestCount)
                assertEquals(oldToken,prefs.getString("token",null))
                assertNotEquals(oldToken,prefs.getString("local_token",null))
            }
        } finally {local.shutdown();online.shutdown()}
    }

}
