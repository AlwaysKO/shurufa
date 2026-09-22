package com.yuyan.imemodule.service

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.prefs.AppPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class EditTestService : ImeService() {
    lateinit var connection: InputConnection
    override fun getCurrentInputConnection(): InputConnection = connection
}
class EditTestConnection(context: Context) : BaseInputConnection(View(context), true) {
    val content = SpannableStringBuilder()
    var success = true
    var stale = false
    var plainSnapshots = false
    var clearOnDelete = false
    var unreadable = false
    var snapshotOverride: String? = null
    init { Selection.setSelection(content, 0) }
    override fun getEditable(): Editable = content
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = if (unreadable) null else ExtractedText().apply {
        text = snapshotOverride ?: if (stale) "" else if (plainSnapshots) content.toString() else SpannableStringBuilder(content)
        startOffset = 0; partialStartOffset = -1; partialEndOffset = -1
        selectionStart = Selection.getSelectionStart(content); selectionEnd = Selection.getSelectionEnd(content)
    }
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        success && super.commitText(text, newCursorPosition)
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        success && super.setComposingText(text, newCursorPosition)
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (!success) return false
        if (clearOnDelete) { content.clear(); BaseInputConnection.removeComposingSpans(content); Selection.setSelection(content, 0); return true }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImeServiceCommittedEditTest {
    @Test fun 成功上屏才入队并保留删除中间插字与清空() = withService { service, connection, db ->
        service.commitText("晚上八点见")
        connection.setSelection(3, 3)
        connection.success = false
        service.deleteSurroundingText(1)
        assertEquals(1, rows(db).size)
        connection.success = true
        service.deleteSurroundingText(1)
        service.commitText("九")
        val events = rows(db)
        assertEquals(listOf("commit", "delete", "commit"), events.map { it.eventType })
        assertEquals(listOf("", "晚上八点见", "晚上点见"), events.map { it.textBefore })
        assertEquals(listOf("晚上八点见", "晚上点见", "晚上九点见"), events.map { it.textAfter })
        assertEquals(1, events.map { it.sessionId }.distinct().size)
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequenceNo })
    }

    @Test fun 拼音不入历史密码和关闭同步不读取或入队() = withService { service, connection, db ->
        service.setComposingText("nihao")
        assertTrue(rows(db).isEmpty())
        service.commitText("你好")
        assertEquals("", rows(db).single().textBefore)
        assertEquals("你好", rows(db).single().textAfter)
        YuyanEmojiCompat.mEditorInfo!!.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        service.commitText("秘密内容")
        assertEquals(1, rows(db).size)
        YuyanEmojiCompat.mEditorInfo!!.inputType = InputType.TYPE_CLASS_TEXT
        CollectionConsent.setEnabled(service, false)
        service.commitText("不同步内容")
        assertEquals(1, rows(db).size)
    }

    @Test fun 选中替换原文留在快照且敏感快照不被旁路采集() = withService { service, connection, db ->
        service.commitText("八点见")
        connection.setSelection(0, 1)
        service.commitText("九")
        assertEquals("八点见", rows(db).last().textBefore)
        assertEquals("九点见", rows(db).last().textAfter)
        connection.content.replace(0, connection.content.length, "password: hidden")
        Selection.setSelection(connection.content, connection.content.length)
        service.commitText("啊")
        assertEquals(2, rows(db).size)
    }

    @Test fun 宿主快照滞后仍保留已接受的输入片段不冒称整句() = withService { service, connection, db ->
        connection.stale = true
        service.commitText("不能漏")
        assertEquals("不能漏", rows(db).single().text)
        assertEquals("false", rows(db).single().metadata?.get("snapshot_complete").toString())
    }

    @Test fun 宿主清空保留旧字后续另组且发送动作切断会话() = withService { service, connection, db ->
        service.commitText("第一句")
        val firstSession = rows(db).single().sessionId
        connection.content.clear()
        Selection.setSelection(connection.content, 0)
        service.onUpdateSelection(3, 3, 0, 0, -1, -1)
        assertEquals("第一句", rows(db).last().text)
        assertEquals("", rows(db).last().textAfter)
        assertEquals(firstSession, rows(db).last().sessionId)
        service.commitText("第二句")
        val secondSession = rows(db).last().sessionId
        assertNotEquals(firstSession, secondSession)
        service.hostEditorActionSender = { true }
        service.performEditorActionAndReport(EditorInfo.IME_ACTION_SEND)
        service.commitText("后续")
        assertNotEquals(secondSession, rows(db).last().sessionId)
    }

    @Test fun 组合文本真正结束组合后才属于已上屏内容() = withService { service, _, db ->
        service.setComposingText("输入完成")
        assertTrue(rows(db).isEmpty())
        service.finishComposingText()
        assertEquals("输入完成", rows(db).single().textAfter)
        service.finishComposingText()
        assertEquals(1, rows(db).size)
    }

    @Test fun 宿主直接取消组合并清空也能记录且不会上报拼音() = withService { service, connection, db ->
        service.commitText("你好")
        service.setComposingText("abc")
        connection.setComposingText("", 1)
        connection.setSelection(0, connection.content.length)
        connection.commitText("", 1)
        service.onUpdateSelection(5, 5, 0, 0, -1, -1)
        assertEquals(2, rows(db).size)
        assertEquals("你好", rows(db).last().text)
        assertEquals("", rows(db).last().textAfter)
        assertFalse(rows(db).any { it.text?.contains("abc") == true })
    }

    @Test fun 仅删除尚未确认的组合文字不产生上屏删除事件() = withService { service, connection, db ->
        service.setComposingText("a")
        connection.clearOnDelete = true
        service.deleteSurroundingText(1)
        assertTrue(rows(db).isEmpty())
    }

    @Test fun 语音提交与清理失败且宿主无样式时不把临时识别混入下次输入() = withService { service, connection, db ->
        val sessionClass = ImeService::class.java.declaredClasses.single { it.simpleName == "VoiceInputSession" }
        val session = sessionClass.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
            .newInstance(1L, "test.chat", connection)
        ImeService::class.java.getDeclaredField("activeVoiceSession").apply { isAccessible = true; set(service, session) }
        ImeService::class.java.getDeclaredMethod("beginVoiceRecognition", VoiceRecognizerMode::class.java,
            Boolean::class.javaPrimitiveType, sessionClass).apply { isAccessible = true }
            .invoke(service, VoiceRecognizerMode.SYSTEM, false, session)
        shadowOf(Looper.getMainLooper()).idle()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        fun results(value: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value)) }
        recognizer.triggerOnPartialResults(results("尚未确认的识别"))
        connection.success = false
        connection.plainSnapshots = true
        recognizer.triggerOnResults(results("最终识别"))
        assertTrue(rows(db).isEmpty())
        connection.success = true
        service.commitText("重新输入")
        assertNull(rows(db).single().textBefore)
        assertEquals("重新输入", rows(db).single().text)
        assertNull(rows(db).single().textAfter)
    }

    @Test fun 宿主不传样式时不猜组合范围而保留原文片段() = withService { service, connection, db ->
        service.commitText("你好")
        connection.plainSnapshots = true
        service.setComposingText("abc")
        service.onUpdateSelection(2, 2, 5, 5, 2, 5)
        service.commitText("英文")
        val events = rows(db)
        assertNull(events.last().textBefore)
        assertNull(events.last().textAfter)
        assertEquals("英文", events.last().text)
        assertNotEquals(events.first().sessionId, events.last().sessionId)
        assertEquals("false", events.last().metadata?.get("snapshot_complete").toString())
    }

    @Test fun 延迟无组合回调不能把刚写入的未确认拼音变成正文() = withService { service, connection, db ->
        service.commitText("你好")
        connection.plainSnapshots = true
        service.setComposingText("ni")
        service.onUpdateSelection(0, 0, 2, 2, -1, -1)
        service.commitText("你")
        assertNull(rows(db).last().textBefore)
        assertFalse(rows(db).any { it.textBefore?.contains("ni") == true })
    }

    @Test fun 正文有重复拼音时不采信旧组合范围() = withService { service, connection, db ->
        service.commitText("ni正文")
        connection.plainSnapshots = true
        service.setComposingText("ni")
        service.onUpdateSelection(0, 0, 2, 2, 0, 2)
        service.commitText("你")
        assertNull(rows(db).last().textBefore)
        assertEquals("你", rows(db).last().text)
        assertNull(rows(db).last().textAfter)
    }

    @Test fun 无组合且宿主不可读时结束组合不伪造空输入() = withService { service, connection, db ->
        connection.unreadable = true
        service.finishComposingText()
        service.finishComposingText()
        assertTrue(rows(db).isEmpty())
    }

    @Test fun 无法确认组合范围时不保存可能滞后的提交后快照() = withService { service, connection, db ->
        service.commitText("你好")
        connection.plainSnapshots = true
        service.setComposingText("ni")
        connection.snapshotOverride = "你好ni"
        service.commitText("你")
        val last = rows(db).last()
        assertEquals("你", last.text)
        assertNull(last.textBefore)
        assertNull(last.textAfter)
    }

    @Test fun 两条提交入口立即同码换词只取消本笔临时奖励() = withService { service, connection, db ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val offline = com.yuyan.imemodule.data.completion.OfflineT9Candidates
        val storeField = offline::class.java.getDeclaredField("store").apply { isAccessible = true }
        (storeField.get(offline) as? LocalInputStore)?.close()
        storeField.set(offline, null)
        offline.init(context)
        val tracker = com.yuyan.inputmethod.RimeEngine::class.java.getDeclaredField("t9CommitTracker").run {
            isAccessible = true; get(com.yuyan.inputmethod.RimeEngine) as com.yuyan.imemodule.data.completion.T9CommitTracker
        }
        try {
            db.learn("3264542", "房价")
            for (cursorEntry in listOf(false, true)) {
                tracker.selected("3264542", "房价", "fang jia")
                if (cursorEntry) service.commitText("房价", 1) else service.commitText("房价")
                assertEquals(2L, db.learned("3264542").first { it.text == "房价" }.count)
                assertEquals(1L, db.dictionaryExport().first { it.kind == "choice" && it.text == "房价" }.count)
                service.deleteSurroundingText(1)
                service.deleteSurroundingText(1)
                tracker.selected("3264542", "放假", "fang jia")
                if (cursorEntry) service.commitText("放假", 1) else service.commitText("放假")
                assertEquals(1L, db.learned("3264542").first { it.text == "房价" }.count)
                assertFalse(db.pendingReports(com.yuyan.imemodule.data.collect.ServerConfig.eventTargets.first()).any { it.kind == "personal_choice" })
                // 在旧词后继续输入，验证中间位置同样受连续快照约束。
                service.commitText("呀")
            }
        } finally {
            tracker.clear()
            (storeField.get(offline) as? LocalInputStore)?.close(); storeField.set(offline, null)
        }
    }

    private fun rows(db: LocalInputStore) = db.targets().firstOrNull()?.let { db.pending(it) }.orEmpty()
    private fun withService(test: (EditTestService, EditTestConnection, LocalInputStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Launcher::class.java.getDeclaredField("context").apply { isAccessible = true; set(Launcher.instance, context) }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        val field = DataCollector::class.java.getDeclaredField("eventStore").apply { isAccessible = true }
        (field.get(DataCollector) as? LocalInputStore)?.close(); field.set(DataCollector, null)
        context.deleteDatabase("local_input.db")
        CollectionConsent.setEnabled(context, true)
        YuyanEmojiCompat.mEditorInfo = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.chat"; fieldId = 1 }
        val service = Robolectric.buildService(EditTestService::class.java).get()
        val connection = EditTestConnection(context)
        service.connection = connection
        val db = LocalInputStore(context)
        try { test(service, connection, db) } finally {
            CollectionConsent.setEnabled(context, false)
            db.close()
            (field.get(DataCollector) as? LocalInputStore)?.close(); field.set(DataCollector, null)
            context.deleteDatabase("local_input.db")
            YuyanEmojiCompat.mEditorInfo = null
        }
    }
}
