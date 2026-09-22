package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.completion.OfflineT9Candidates
import com.yuyan.imemodule.data.completion.T9Lexicon
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
class DictionaryAdditionsTest {
    private inline fun <T> LocalInputStore.withStore(block:(LocalInputStore)->T):T=try {block(this)} finally {close()}
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun word(i:Int)= (0..8).joinToString("") { if(i and (1 shl it)==0) "甲" else "乙" }
    private fun reading(i:Int)= (0..8).joinToString(" ") { if(i and (1 shl it)==0) "jia" else "yi" }
    private fun code(pinyin:String)=T9Lexicon.digits(pinyin.replace(" ",""))
    private fun batch(words:List<Pair<String,String>>, start:Int=1, preferred:Boolean=false)=
        """{"entries":[${words.mapIndexed { i,(text,pinyin)-> """{"cursor":${start+i},"text":"$text","pinyin":"$pinyin","preferred":$preferred}""" }.joinToString(",")}],"cursor":${start+words.size-1},"has_more":false}"""
    private fun response(server:MockWebServer,body:String,status:Int=200) { server.enqueue(MockResponse().setResponseCode(status).setBody(body)) }
    @Test fun `本机200线上100本地20取并集且小集合空批和旧快照不删词不增次数`() {
        val a=MockWebServer();val b=MockWebServer();a.start();b.start()
        val name="additions-${UUID.randomUUID()}.db"
        try { LocalInputStore(context,name).withStore { store ->
            for(i in 0 until 200) store.rememberWord(word(i),reading(i),"selection")
            store.learn("93663","怎么")
            val before=store.dictionaryExport()
            val prefs=context.getSharedPreferences(name,0)
            fun sync(server:MockWebServer,prefix:String)=PersonalDictionarySync(store,prefs,OkHttpClient(),"phone",server.url("/").toString(),{true},{"complete" to 0},false,prefix)
            val online=sync(a,"online_");val local=sync(b,"local_")
            for((server,sync,range) in listOf(Triple(a,online,200 until 300),Triple(b,local,300 until 320))) {
                response(server,"""{"additions_supported":true}""");response(server,"{}")
                response(server,batch(range.map {word(it) to reading(it)}));response(server,"{\"ok\":true}")
                assertTrue(sync.run());assertEquals(4,server.requestCount)
                assertTrue(server.takeRequest().body.readUtf8().contains("\"additions_supported\":true"))
                server.takeRequest();assertTrue(server.takeRequest().path!!.endsWith("/additions?after=0"));server.takeRequest()
            }
            fun assertUnion() { for(i in 0 until 320) assertTrue("missing $i",store.personalWords(code(reading(i))).any {it.text==word(i)}) }
            assertUnion();assertEquals(before,store.dictionaryExport())
            response(b,"""{"additions_supported":true,"has_report":true}""")
            response(b,batch(listOf(word(0) to reading(0),word(210) to reading(210)),21));response(b,"{\"ok\":true}")
            assertTrue(local.run());b.takeRequest();assertTrue(b.takeRequest().path!!.endsWith("after=20"));b.takeRequest()
            response(a,"""{"additions_supported":true,"has_report":true}""")
            response(a,"""{"entries":[],"cursor":100,"has_more":false}""")
            assertTrue(online.run());assertUnion()
            store.applyDictionarySnapshot(DictionarySnapshot("g","a".repeat(64),emptyList(),emptyList()),"phone")
            assertUnion();assertEquals(before,store.dictionaryExport())
        } } finally { a.shutdown();b.shutdown();context.deleteDatabase(name) }
    }
    @Test fun `确认失败重取同批坏批不写不确认`() {
        val server=MockWebServer();server.start();val name="retry-${UUID.randomUUID()}.db"
        try { LocalInputStore(context,name).withStore { store ->
            val sync=PersonalDictionarySync(store,context.getSharedPreferences(name,0),OkHttpClient(),"phone",server.url("/").toString(),{true},{"complete" to 0},false)
            response(server,"""{"additions_supported":true}""");response(server,"{}")
            response(server,batch(listOf("泰鲮" to "tai ling")));response(server,"{}",503)
            assertFalse(sync.run());assertEquals("泰鲮",store.personalWords("8245464").single().text)
            repeat(4) { server.takeRequest() }
            response(server,"""{"additions_supported":true,"has_report":true}""")
            response(server,batch(listOf("泰鲮" to "tai ling")));response(server,"{\"ok\":true}")
            assertTrue(sync.run());server.takeRequest();assertTrue(server.takeRequest().path!!.endsWith("after=0"));server.takeRequest()
            response(server,"""{"additions_supported":true,"has_report":true}""")
            response(server,batch(listOf("泰凌" to "tai ling","不合法" to "wrong"),2))
            assertFalse(sync.run());assertEquals(listOf("泰鲮"),store.personalWords("8245464").map {it.text})
            assertEquals(9,server.requestCount);assertTrue(store.dictionaryExport().isEmpty())
        } } finally {server.shutdown();context.deleteDatabase(name)}
    }
    @Test fun `手工词同步后离线优先且不放宽内部拼写停用不复活`() {
        val field=OfflineT9Candidates::class.java.getDeclaredField("store").apply {isAccessible=true}
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close();field.set(OfflineT9Candidates,null);context.deleteDatabase("local_input.db")
        val server=MockWebServer();server.start()
        try {
            OfflineT9Candidates.init(context);val store=field.get(OfflineT9Candidates) as LocalInputStore
            val sync=PersonalDictionarySync(store,context.getSharedPreferences(UUID.randomUUID().toString(),0),OkHttpClient(),"phone",server.url("/").toString(),{true},{"complete" to 0},false)
            response(server,"""{"additions_supported":true}""");response(server,"{}")
            response(server,batch(listOf("泰鲮" to "tai ling"),preferred=true));response(server,"{\"ok\":true}")
            assertTrue(sync.run())
            assertEquals("泰鲮",OfflineT9Candidates.select("8245464",listOf("太灵"),listOf("tai ling")).firstPage.first().text)
            assertFalse(OfflineT9Candidates.query("825464").any {it.text=="泰鲮"});assertTrue(store.dictionaryExport().isEmpty())
            store.applyDictionarySnapshot(DictionarySnapshot("g","b".repeat(64),emptyList(),listOf(DictionaryPolicy("泰鲮","disabled"))),"phone")
            assertFalse(OfflineT9Candidates.query("8245464").any {it.text=="泰鲮"})
        } finally { (field.get(OfflineT9Candidates) as? LocalInputStore)?.close();field.set(OfflineT9Candidates,null);context.deleteDatabase("local_input.db");server.shutdown() }
    }
    @Test fun `增量格式次序空批游标必须完整一致`() {
        val first=DictionaryAddition(2,"泰鲮","tai ling")
        val second=DictionaryAddition(3,"泰凌","tai ling")
        assertTrue(DictionaryAdditions(listOf(first,second),3,false).validAfter(0))
        assertFalse(DictionaryAdditions(listOf(second,first),2,false).validAfter(0))
        assertFalse(DictionaryAdditions(listOf(first,first),2,false).validAfter(0))
        assertFalse(DictionaryAdditions(listOf(first),3,false).validAfter(0))
        assertFalse(DictionaryAdditions(listOf(first),2,false).validAfter(2))
        assertFalse(DictionaryAdditions(emptyList(),3,true).validAfter(3))
        assertFalse(DictionaryAdditions(emptyList(),0,false).validAfter(3))
        assertFalse(DictionaryAdditions(List(501) { first },2,false).validAfter(0))
        assertFalse(first.copy(pinyin="tai").valid())
        assertFalse(first.copy(cursor=9_007_199_254_740_992L).valid())
        assertFalse(first.copy(text="验证码",pinyin="yan zheng ma").valid())
    }
    @Test fun `升级v7保留原词和计数追加词持久化且优先级只升不降`() {
        val name="upgrade-${UUID.randomUUID()}.db"
        try {
            LocalInputStore(context,name).withStore { store ->
                store.learn("93663","怎么")
                store.writableDatabase.execSQL("INSERT INTO dictionary_remote_word(device_id,text,pinyin,full_code,source) VALUES('old','旧词','jiu ci','54824','selection')")
                store.writableDatabase.execSQL("DROP TABLE dictionary_added_word")
                store.writableDatabase.execSQL("DROP TABLE dictionary_addition_cursor")
                store.writableDatabase.version=7
            }
            LocalInputStore(context,name).withStore { store ->
                assertEquals(9,store.writableDatabase.version)
                store.writableDatabase.execSQL("DELETE FROM dictionary_remote_word")
                assertEquals("旧词",store.personalWords("54824").single().text)
                val word=DictionaryAddition(1,"泰鲮","tai ling")
                store.mergeDictionaryAdditions(listOf(word))
                assertTrue(store.personalWords("8245464",true).isEmpty())
                store.mergeDictionaryAdditions(listOf(word.copy(preferred=true)))
                store.mergeDictionaryAdditions(listOf(word))
                assertEquals(1,store.personalWords("8245464",true).size)
                store.saveDictionaryAdditionCursor("online",3)
                store.saveDictionaryAdditionCursor("local",1)
            }
            LocalInputStore(context,name).withStore { store ->
                assertEquals(1L,store.learned("93663").single().count)
                assertEquals(1,store.personalWords("8245464",true).size)
                assertEquals(3L,store.dictionaryAdditionCursor("online"))
                assertEquals(1L,store.dictionaryAdditionCursor("local"))
                assertEquals(0L,store.dictionaryAdditionCursor("new"))
                // 真正SQLite中途失败必须回滚首条插入，不只是预校验。
                store.writableDatabase.execSQL("CREATE TRIGGER test_reject BEFORE INSERT ON dictionary_added_word WHEN NEW.text='泰零' BEGIN SELECT RAISE(ABORT,'test failure'); END")
                try {
                    store.mergeDictionaryAdditions(listOf(DictionaryAddition(4,"泰凌","tai ling"),DictionaryAddition(5,"泰零","tai ling")))
                    fail("must roll back")
                } catch(_:android.database.SQLException) {}
                assertEquals(listOf("泰鲮"),store.personalWords("8245464").map {it.text})
            }
        } finally {context.deleteDatabase(name)}
    }
    @Test fun `服务明确游标重置时仅重放本目标词库不丢手机已有词`() {
        val server=MockWebServer();server.start();val name="reset-${UUID.randomUUID()}.db"
        try {LocalInputStore(context,name).withStore { store ->
            val url=server.url("/").toString()
            val key=url.trimEnd('/')+"/api/v1/mobile/dictionary#phone"
            store.saveDictionaryAdditionCursor(key,10)
            store.saveDictionaryAdditionCursor("other",50)
            store.mergeDictionaryAdditions(listOf(DictionaryAddition(1,"泰鲮","tai ling")))
            val sync=PersonalDictionarySync(store,context.getSharedPreferences(name,0),OkHttpClient(),"phone",url,{true},{"complete" to 0},false)
            response(server,"""{"additions_supported":true}""");response(server,"{}")
            response(server,"""{"code":"dictionary_cursor_reset"}""",409)
            response(server,batch(listOf("泰凌" to "tai ling")));response(server,"{\"ok\":true}")
            assertTrue(sync.run());assertEquals(5,server.requestCount)
            server.takeRequest();server.takeRequest()
            assertTrue(server.takeRequest().path!!.endsWith("after=10"))
            assertTrue(server.takeRequest().path!!.endsWith("after=0"))
            assertEquals(2,store.personalWords("8245464").size)
            assertEquals(1L,store.dictionaryAdditionCursor(key));assertEquals(50L,store.dictionaryAdditionCursor("other"))
            // 未指定重置的409不能复位游标，也不能标为成功。
            response(server,"""{"additions_supported":true,"has_report":true}""");response(server,"{}",409)
            assertFalse(sync.run());assertEquals(1L,store.dictionaryAdditionCursor(key))
        }} finally {server.shutdown();context.deleteDatabase(name)}
    }

    @Test fun `手机既有远端恢复词也属于并集较小主控快照不隐式删词`() {
        val name="remote-union-${UUID.randomUUID()}.db"
        try {LocalInputStore(context,name).withStore { store ->
            val remote=DictionaryRecord("word","泰鲮","","tai ling","selection",0,0.0,0,"old-phone")
            store.applyDictionarySnapshot(DictionarySnapshot("g","c".repeat(64),listOf(remote),emptyList()),"phone")
            assertEquals("泰鲮",store.personalWords("8245464").single().text)
            store.mergeDictionaryAdditions(listOf(DictionaryAddition(1,"泰凌","tai ling")))
            store.applyDictionarySnapshot(DictionarySnapshot("g","d".repeat(64),emptyList(),emptyList()),"phone")
            assertEquals(setOf("泰鲮","泰凌"),store.personalWords("8245464").map {it.text}.toSet())
            assertTrue(store.dictionaryExport().isEmpty())
            store.applyDictionarySnapshot(DictionarySnapshot("g","e".repeat(64),emptyList(),listOf(DictionaryPolicy("泰鲮","deleted"))),"phone")
            assertEquals(listOf("泰凌"),store.personalWords("8245464").map {it.text})
        }} finally {context.deleteDatabase(name)}
    }

    @Test fun `策略缺项不能复活明确停用词只有显式启用才能恢复`() {
        val name="policy-union-${UUID.randomUUID()}.db"
        try {LocalInputStore(context,name).withStore { store ->
            store.mergeDictionaryAdditions(listOf(DictionaryAddition(1,"泰鲮","tai ling",true)))
            val empty=DictionarySnapshot("g","e".repeat(64),emptyList(),emptyList())
            store.applyDictionarySnapshot(empty.copy(policies=listOf(DictionaryPolicy("泰鲮","disabled"))),"phone")
            store.applyDictionarySnapshot(empty,"phone")
            assertTrue(store.personalWords("8245464").isEmpty())
            store.applyDictionarySnapshot(empty.copy(policies=listOf(DictionaryPolicy("泰鲮","enabled"))),"phone")
            assertEquals("泰鲮",store.personalWords("8245464",true).single().text)
        }} finally {context.deleteDatabase(name)}
    }

    @Test fun `重叠两端交换顺序重复小批得到同一并集且手工偏好不降级`() {
        val online=listOf(DictionaryAddition(1,"泰鲮","tai ling"),DictionaryAddition(2,"泰凌","tai ling"))
        val local=listOf(DictionaryAddition(1,"泰鲮","tai ling",true))
        for(batches in listOf(listOf(online,local),listOf(local,online))) {
            val name="order-${UUID.randomUUID()}.db"
            try {LocalInputStore(context,name).withStore { store ->
                repeat(2) {batches.forEach {store.mergeDictionaryAdditions(it)}}
                assertEquals(setOf("泰鲮","泰凌"),store.personalWords("8245464").map {it.text}.toSet())
                assertEquals(listOf("泰鲮"),store.personalWords("8245464",true).map {it.text})
                assertTrue(store.dictionaryExport().isEmpty())
            }} finally {context.deleteDatabase(name)}
        }
    }
    @Test fun `分页单次最多20页下轮从已确认游标继续而不是重置`() {
        val server=MockWebServer();server.start();val name="pages-${UUID.randomUUID()}.db"
        try {LocalInputStore(context,name).withStore { store ->
            val url=server.url("/").toString()
            val key=url.trimEnd('/')+"/api/v1/mobile/dictionary#phone"
            val sync=PersonalDictionarySync(store,context.getSharedPreferences(name,0),OkHttpClient(),"phone",url,{true},{"complete" to 0},false)
            response(server,"""{"additions_supported":true}""");response(server,"{}")
            repeat(20) { page ->
                response(server,batch(listOf("泰鲮" to "tai ling"),page+1).replace("\"has_more\":false","\"has_more\":true"))
                response(server,"{\"ok\":true}")
            }
            assertTrue(sync.run());assertEquals(42,server.requestCount)
            assertEquals(20L,store.dictionaryAdditionCursor(key));repeat(42) {server.takeRequest()}
            response(server,"""{"additions_supported":true,"has_report":true}""")
            response(server,batch(listOf("泰凌" to "tai ling"),21));response(server,"{\"ok\":true}")
            assertTrue(sync.run());server.takeRequest();assertTrue(server.takeRequest().path!!.endsWith("after=20"))
            assertEquals(21L,store.dictionaryAdditionCursor(key));assertEquals(2,store.personalWords("8245464").size)
        }} finally {server.shutdown();context.deleteDatabase(name)}
    }

}
