package com.yuyan.imemodule.data.collect

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CollectorPrivacyIntegrationTest {
 @Before fun resetStore() {
  val ctx = ApplicationProvider.getApplicationContext<Context>()
  val field = DataCollector::class.java.getDeclaredField("eventStore").apply { isAccessible = true }
  (field.get(DataCollector) as? LocalInputStore)?.close()
  field.set(DataCollector, null)
  ctx.deleteDatabase("local_input.db")
  // 与输入法启动已打开本地库的条件一致，避免两个测试 helper 同时建空库。
  val initial = LocalInputStore(ctx)
  try { initial.writableDatabase } finally { initial.close() }
 }

 @Test fun `真实记录入口授权前和密码框不落盘撤销后不追加`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit()
  YuyanEmojiCompat.setEditorInfo(EditorInfo().apply {inputType=InputType.TYPE_CLASS_TEXT})
  DataCollector.recordEvent(ctx,"commit",text="未授权")
  val store=LocalInputStore(ctx)
  try {
   val local="http://127.0.0.1:3000"
   assertTrue(store.pending(local).isEmpty())
   CollectionConsent.setEnabled(ctx,true)
   YuyanEmojiCompat.setEditorInfo(EditorInfo().apply {inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD})
   for (kind in listOf("commit","voice","delete","clipboard_change")) DataCollector.recordEvent(ctx,kind,text="do-not-store")
   assertTrue(store.pending(local).isEmpty())
   YuyanEmojiCompat.setEditorInfo(EditorInfo().apply {inputType=InputType.TYPE_CLASS_TEXT})
   ImageUploadRuntime.noteKeyActivity()
   assertTrue(DataCollector.recordEvent(ctx,"commit",text="普通聊天"))
   assertTrue("打字时不得落盘", store.pending(local).isEmpty())
   ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
   await { store.pending(local).size == 1 }
   assertEquals("普通聊天",store.pending(local).single().text)
   CollectionConsent.setEnabled(ctx,false)
   DataCollector.recordEvent(ctx,"commit",text="已撤销")
   assertFalse(DataCollector.enqueueReport(ctx,"phrase_upsert","{\"content\":\"已撤销\"}"))
   assertEquals(1,store.pending(local).size)
   assertTrue(store.pendingReports(local).isEmpty())
  } finally {store.close();CollectionConsent.setEnabled(ctx,false);YuyanEmojiCompat.setEditorInfo(null)}
 }
 @Test fun `缓冲事件保留发生时间顺序和原输入框授权快照`() {
  val ctx = ApplicationProvider.getApplicationContext<Context>()
  CollectionConsent.setEnabled(ctx, true)
  YuyanEmojiCompat.setEditorInfo(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
  val store = LocalInputStore(ctx)
  try {
   ImageUploadRuntime.noteKeyActivity()
   val earliest = System.currentTimeMillis()
   assertTrue(DataCollector.recordEvent(ctx, "commit", text = "第一条", sessionId = "session", sequenceNo = 7))
   assertTrue(DataCollector.recordEvent(ctx, "delete", text = "第二条", sessionId = "session", sequenceNo = 8))
   val latest = System.currentTimeMillis()
   assertTrue(store.pending(ServerConfig.eventTargets.first()).isEmpty())
   Thread.sleep(1_050)
   // 等待时切到密码框不能重新读取编辑器、误把旧事件归到新输入框。
   YuyanEmojiCompat.setEditorInfo(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
   ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
   await { store.pending(ServerConfig.eventTargets.first()).size == 2 }
   val rows = store.pending(ServerConfig.eventTargets.first())
   assertEquals(listOf("第一条", "第二条"), rows.map { it.text })
   assertEquals(listOf(7L, 8L), rows.map { it.sequenceNo })
   assertEquals(listOf("session", "session"), rows.map { it.sessionId })
   assertEquals(2, rows.map { it.id }.distinct().size)
   val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
   rows.forEach { assertTrue(format.parse(it.occurredAt)!!.time in (earliest - 999)..latest) }
  } finally { store.close(); CollectionConsent.setEnabled(ctx, false); YuyanEmojiCompat.setEditorInfo(null) }
 }

 @Test fun `关闭再开启不复活撤权前尚未保存的事件`() {
  val ctx = ApplicationProvider.getApplicationContext<Context>()
  CollectionConsent.setEnabled(ctx, true)
  YuyanEmojiCompat.setEditorInfo(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
  val store = LocalInputStore(ctx)
  try {
   ImageUploadRuntime.noteKeyActivity()
   assertTrue(DataCollector.recordEvent(ctx, "commit", text = "撤权前未落盘"))
   CollectionConsent.setEnabled(ctx, false)
   CollectionConsent.setEnabled(ctx, true)
   assertTrue(DataCollector.recordEvent(ctx, "commit", text = "重新授权后"))
   ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
   await { store.pending(ServerConfig.eventTargets.first()).any { it.text == "重新授权后" } }
   assertEquals(listOf("重新授权后"), store.pending(ServerConfig.eventTargets.first()).map { it.text })
  } finally { store.close(); CollectionConsent.setEnabled(ctx, false); YuyanEmojiCompat.setEditorInfo(null) }
 }

 @Test fun `诊断元数据深拷贝且超额拒绝不假称已接收`() {
  val ctx = ApplicationProvider.getApplicationContext<Context>()
  CollectionConsent.setEnabled(ctx, true)
  YuyanEmojiCompat.setEditorInfo(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
  val store = LocalInputStore(ctx)
  try {
   ImageUploadRuntime.noteKeyActivity()
   val child = mutableMapOf<String, JsonElement>("value" to JsonPrimitive("原始值"))
   val array = mutableListOf<JsonElement>(JsonObject(child))
   val metadata = JsonObject(mapOf("items" to JsonArray(array)))
   assertTrue(DataCollector.recordEvent(ctx, "commit", text = "普通事件", metadata = metadata))
   child["value"] = JsonPrimitive("后来修改")
   array.clear()
   assertFalse(DataCollector.recordEvent(ctx, "commit", text = "超额诊断",
       metadata = JsonObject(mapOf("oversize" to JsonPrimitive("x".repeat(40_000))))))
   assertEquals(1, DataCollector.bufferedEventCount)
   assertTrue(store.pending(ServerConfig.eventTargets.first()).isEmpty())
   ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
   await { store.pending(ServerConfig.eventTargets.first()).size == 1 }
   val saved = store.pending(ServerConfig.eventTargets.first()).single()
   assertEquals("原始值", saved.metadata!!.getValue("items").jsonArray.single().jsonObject.getValue("value").jsonPrimitive.content)
  } finally { store.close(); CollectionConsent.setEnabled(ctx, false); YuyanEmojiCompat.setEditorInfo(null) }
 }

 private fun await(condition: () -> Boolean) {
  val deadline = System.nanoTime() + 5_000_000_000L
  while ((!condition() || DataCollector.bufferedEventCount > 0) && System.nanoTime() < deadline) Thread.sleep(20)
  assertTrue("等待后台落盘超时", condition())
  assertEquals(0, DataCollector.bufferedEventCount)
 }
}
