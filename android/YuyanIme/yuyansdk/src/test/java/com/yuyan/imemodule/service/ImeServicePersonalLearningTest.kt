package com.yuyan.imemodule.service

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.imemodule.data.completion.OfflineT9Candidates
import com.yuyan.imemodule.data.completion.T9CommitTracker
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.inputmethod.RimeEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImeServicePersonalLearningTest {
    @Test fun `两条宿主提交入口都只在成功允许学习时保存一次整句与读音`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Launcher::class.java.getDeclaredField("context").apply { isAccessible = true; set(Launcher.instance, context) }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        CollectionConsent.setEnabled(context, false)
        val storeField = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        (storeField.get(OfflineT9Candidates) as? LocalInputStore)?.close()
        storeField.set(OfflineT9Candidates, null)
        context.deleteDatabase("local_input.db")
        OfflineT9Candidates.init(context)
        val service = Robolectric.buildService(ImeService::class.java).get()
        val tracker = RimeEngine::class.java.getDeclaredField("t9CommitTracker").run {
            isAccessible = true; get(RimeEngine) as T9CommitTracker
        }
        val db = LocalInputStore(context)
        try {
            var expected = 0L
            for (cursorEntry in listOf(false, true)) {
                for ((success, record, inputType) in listOf(
                    Triple(false, true, InputType.TYPE_CLASS_TEXT),
                    Triple(true, false, InputType.TYPE_CLASS_TEXT),
                    Triple(true, true, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
                    Triple(true, true, InputType.TYPE_CLASS_TEXT),
                )) {
                    YuyanEmojiCompat.mEditorInfo = EditorInfo().apply { this.inputType = inputType }
                    service.hostTextCommitter = { _, _ -> success }
                    tracker.segment("94363362", "真的", "zhen de", null)
                    tracker.segment("", "吗", "ma", "真的吗")
                    if (cursorEntry) service.commitText("真的吗", 1, record) else service.commitText("真的吗", record)
                    if (success && record && inputType == InputType.TYPE_CLASS_TEXT) expected++
                    // 重复回调没有新的选择证据，不增加次数。
                    if (cursorEntry) service.commitText("真的吗", 1, record) else service.commitText("真的吗", record)
                    assertEquals(expected, db.learned("94363362").firstOrNull()?.count ?: 0L)
                }
            }
            assertEquals("zhen de ma", db.personalWords("94363362").single().pinyin)
            assertTrue(db.reportTargets().isEmpty())
        } finally {
            tracker.clear()
            db.close()
            (storeField.get(OfflineT9Candidates) as? LocalInputStore)?.close()
            storeField.set(OfflineT9Candidates, null)
            context.deleteDatabase("local_input.db")
            YuyanEmojiCompat.mEditorInfo = null
        }
    }
}
