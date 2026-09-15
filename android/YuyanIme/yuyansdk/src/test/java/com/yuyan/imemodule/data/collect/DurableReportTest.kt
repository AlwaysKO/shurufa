package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
@Config(sdk = [28])
class DurableReportTest {
 @Test fun `通用报告重启保留且两个目标分别确认`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
  val report=PendingReport("r1", "location", "{\"latitude\":31,\"longitude\":121}")
  LocalInputStore(ctx,name).withStore { it.enqueueReport(report,listOf("a","b")); it.acknowledgeReports("a",listOf("r1")) }
  LocalInputStore(ctx,name).withStore { assertTrue(it.pendingReports("a").isEmpty()); assertEquals(listOf(report),it.pendingReports("b")); it.acknowledgeReports("b",listOf("r1")); assertTrue(it.reportTargets().isEmpty()) }
  ctx.deleteDatabase(name)
 }
 @Test fun `反馈失败保留且确认必须包含对应ID`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
  val server=MockWebServer(); server.start()
  LocalInputStore(ctx,name).withStore { store ->
   val target=server.url("/").toString().trimEnd('/'); store.enqueueReport(PendingReport("r1","completion_feedback","{}"),listOf(target))
   val sender=EventDelivery(store,OkHttpClient(),"device","{}")
   server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setResponseCode(503))
   assertFalse(sender.flush(target)); assertEquals(1,store.pendingReports(target).size)
   server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody("{\"ok\":true,\"id\":\"wrong\"}"))
   assertFalse(sender.flush(target)); assertEquals(1,store.pendingReports(target).size)
   server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody("{\"ok\":true,\"id\":\"r1\"}"))
   assertTrue(sender.flush(target)); assertTrue(store.pendingReports(target).isEmpty())
  }; server.shutdown(); ctx.deleteDatabase(name)
 }
 @Test fun `本地学习与待传候选快照同事务持久化`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
  LocalInputStore(ctx,name).withStore { store ->
   store.learn("xuq","需求",listOf("a","b"))
   assertEquals(1L,store.learned("xuq").first().count)
   assertEquals("personal_choice",store.pendingReports("a").first().kind)
   assertEquals(store.pendingReports("a"),store.pendingReports("b"))
  }; ctx.deleteDatabase(name)
 }
 @Test fun `关闭定位不能让队列前部的位置阻塞候选补传`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
  val server=MockWebServer(); server.start()
  LocalInputStore(ctx,name).withStore { store ->
   val target=server.url("/").toString().trimEnd('/')
   repeat(20) {store.enqueueReport(PendingReport("loc$it","location","{}"),listOf(target))}
   store.enqueueReport(PendingReport("choice","personal_choice","{}"),listOf(target))
   server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody("{\"ok\":true,\"id\":\"choice\"}"))
   assertTrue(EventDelivery(store,OkHttpClient(),"device","{}",allowed={it!="location"}).flush(target))
   assertEquals(2,server.requestCount)
  };server.shutdown();ctx.deleteDatabase(name)
 }
 @Test fun `永久失败报告保留但不占住所有后续报告的发送机会`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
  val server=MockWebServer(); server.start()
  LocalInputStore(ctx,name).withStore { store ->
   val target=server.url("/").toString().trimEnd('/')
   repeat(20) {store.enqueueReport(PendingReport("bad$it","phrase_upsert","{}"),listOf(target))}
   store.enqueueReport(PendingReport("good","personal_choice","{}"),listOf(target))
   server.enqueue(MockResponse().setBody("{\"ok\":true}"));repeat(20){server.enqueue(MockResponse().setResponseCode(400))}
   assertFalse(EventDelivery(store,OkHttpClient(),"device","{}").flush(target))
   assertEquals("good",store.pendingReports(target).first().id)
  };server.shutdown();ctx.deleteDatabase(name)
 }
 @Test fun `大媒体报告限制每轮内存占用并可重新读取完整内容`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
  val payload="{\"file_base64\":\""+"A".repeat(3_000_000)+"\"}"
  LocalInputStore(ctx,name).withStore { store -> repeat(3) { store.enqueueReport(PendingReport("big$it","chat_asset",payload),listOf("a")) } }
  LocalInputStore(ctx,name).withStore { store ->
   val pending=store.pendingReports("a");assertEquals(1,pending.size);assertEquals(payload,pending.single().payload)
  };ctx.deleteDatabase(name)
 }
 @Test fun `每轮最多发送20个报告避免恢复网络时洪峰`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
  LocalInputStore(ctx,name).withStore { store ->
   repeat(45) { store.enqueueReport(PendingReport("r$it","location","{}"),listOf("a")) }
   assertEquals(20,store.pendingReports("a").size)
  }; ctx.deleteDatabase(name)
 }
 @Test fun `聊天资源和消息优先于普通积压报告`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
  LocalInputStore(ctx,name).withStore { store ->
   repeat(20) { store.enqueueReport(PendingReport("normal$it","personal_choice","{}"),listOf("a")) }
   store.enqueueReport(PendingReport("message","chat_messages","{}"),listOf("a"))
   store.enqueueReport(PendingReport("asset","chat_asset","{}"),listOf("a"))
   assertEquals(listOf("chat_asset","chat_messages"),store.pendingReports("a",limit=2).map { it.kind })
  }; ctx.deleteDatabase(name)
 }
}

private inline fun <T> LocalInputStore.withStore(block: (LocalInputStore) -> T): T = try { block(this) } finally { close() }
