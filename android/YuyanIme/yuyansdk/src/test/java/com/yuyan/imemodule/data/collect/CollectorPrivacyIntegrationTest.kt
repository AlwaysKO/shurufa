package com.yuyan.imemodule.data.collect

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CollectorPrivacyIntegrationTest {
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
   DataCollector.recordEvent(ctx,"commit",text="普通聊天")
   assertEquals("普通聊天",store.pending(local).single().text)
   CollectionConsent.setEnabled(ctx,false)
   DataCollector.recordEvent(ctx,"commit",text="已撤销")
   assertFalse(DataCollector.enqueueReport(ctx,"phrase_upsert","{\"content\":\"已撤销\"}"))
   assertEquals(1,store.pending(local).size)
   assertTrue(store.pendingReports(local).isEmpty())
  } finally {store.close();CollectionConsent.setEnabled(ctx,false);YuyanEmojiCompat.setEditorInfo(null)}
 }
}
