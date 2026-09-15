package com.yuyan.imemodule.data.completion

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.os.CancellationSignal
import android.provider.UserDictionary
import android.util.Log
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinOutputFormat
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinToneType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinVCharType
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.imemodule.utils.thread.ThreadPoolUtils
import java.util.concurrent.TimeUnit

internal data class DictionaryMigrationResult(val status: String, val imported: Int = 0)

/** 仅访问系统公开用户词典。安装后首次进程初始化尝试，不接触其他应用私有数据。 */
internal class SystemDictionaryMigration(
    private val prefs: SharedPreferences,
    private val store: LocalInputStore,
    private val query: () -> Cursor?,
    private val readings: (Set<String>) -> Map<String, List<String>>,
) {
    fun run(): DictionaryMigrationResult = synchronized(lock) {
        if (prefs.contains("status")) return@synchronized DictionaryMigrationResult(
            prefs.getString("status", "unavailable")!!, prefs.getInt("imported", 0))
        val result = try {
            val cursor = query()
            if (cursor == null) DictionaryMigrationResult("unavailable") else cursor.use { c ->
                val wordColumn = c.getColumnIndexOrThrow(UserDictionary.Words.WORD)
                val shortcutColumn = c.getColumnIndex(UserDictionary.Words.SHORTCUT)
                val words = linkedMapOf<String, MutableSet<String>>()
                var rows = 0
                while (c.moveToNext()) {
                    check(++rows <= 50_000) { "Dictionary row limit" }
                    val text = c.getString(wordColumn)?.trim().orEmpty()
                    if (text.length !in 1..30 || text.any { it !in '\u4e00'..'\u9fff' }) continue
                    val shortcut = if (shortcutColumn >= 0) c.getString(shortcutColumn).orEmpty() else ""
                    words.getOrPut(text) { linkedSetOf() }.add(shortcut)
                }
                // 批量匹配只扫描一次词库，不能对每个导入词重扫十余万条。
                val knownReadings = readings(words.keys)
                val db = store.writableDatabase
                db.beginTransaction()
                try {
                    words.forEach { (text, shortcuts) ->
                        val known = knownReadings[text].orEmpty().mapNotNull { PersonalWordReading.normalize(text, it) }
                        val explicit = shortcuts.mapNotNull { verifiedShortcut(text, it) }
                        val resolved = (known + explicit).distinct().ifEmpty { listOf("") }
                        resolved.forEach { store.rememberWord(text, it, "system_dictionary") }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                DictionaryMigrationResult("complete", words.size)
            }
        } catch (_: SecurityException) {
            DictionaryMigrationResult("permission_denied")
        } catch (_: Exception) {
            DictionaryMigrationResult("failed")
        }
        // 数据库先提交。进程中断后重试依靠唯一键去重，不能把导入当作点击次数。
        prefs.edit().putString("status", result.status).putInt("imported", result.imported).commit()
        result
    }

    /** shortcut 是任意缩写，只有逐字属于该汉字的已知读音时才作为显式拼音。 */
    private fun verifiedShortcut(text: String, shortcut: String): String? {
        val reading = PersonalWordReading.normalize(text, shortcut) ?: return null
        val format = HanyuPinyinOutputFormat().apply {
            toneType = HanyuPinyinToneType.WITHOUT_TONE
            vCharType = HanyuPinyinVCharType.WITH_V
        }
        val syllables = reading.split(' ')
        return reading.takeIf {
            text.indices.all { index ->
                syllables[index] in PinyinHelper.toHanYuPinyinMulti(text[index].toString(), format, ",").split(',')
            }
        }
    }

    companion object {
        private val lock = Any()
        fun migrate(context: Context, readings: (Set<String>) -> Map<String, List<String>>): DictionaryMigrationResult {
            val app = context.applicationContext
            val signal = CancellationSignal()
            val timeout = ThreadPoolUtils.schedule({ signal.cancel() }, 5, TimeUnit.SECONDS)
            val store = LocalInputStore(app)
            return try {
                SystemDictionaryMigration(app.getSharedPreferences("system_dictionary_migration_v1", 0), store, {
                    app.contentResolver.query(UserDictionary.Words.CONTENT_URI,
                        arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT), null, null, null, signal)
                }, readings).run().also {
                    // 仅统计状态，绝不把词语内容写入日志或上报。
                    Log.i("DictionaryMigration", "status=${it.status}, imported=${it.imported}")
                }
            } finally { timeout.cancel(false); store.close() }
        }
    }
}
