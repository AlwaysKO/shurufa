package com.yuyan.imemodule.data.collect

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersonalWordQueryIndexTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tables = listOf("personal_word", "dictionary_remote_word", "dictionary_added_word")

    private fun withStore(name: String, block: (LocalInputStore) -> Unit) {
        val store = LocalInputStore(context, name)
        try { block(store) } finally { store.close() }
    }

    private fun assertReadingIndexes(db: SQLiteDatabase) {
        tables.forEach { table ->
            val name = "${table}_reading"
            val columns = db.rawQuery("PRAGMA index_info($name)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(2)) }
            }
            assertEquals(name, listOf("pinyin", "text"), columns)
            val plan = db.rawQuery(
                "EXPLAIN QUERY PLAN SELECT text,pinyin FROM $table WHERE pinyin>=? AND pinyin<? AND length(text)=2 AND pinyin GLOB ?",
                arrayOf("a", "d", "[abc]* [jkl]*"),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString(" ") }
            assertTrue(plan, plan.contains(name) && plan.contains("SEARCH"))
            assertFalse(plan, plan.contains("SCAN $table"))
        }
    }

    @Test fun `新库简拼读音范围使用覆盖索引`() {
        val name = "reading-index-${UUID.randomUUID()}.db"
        withStore(name) { store -> assertReadingIndexes(store.writableDatabase) }
        context.deleteDatabase(name)
    }

    @Test fun `版本十二升级只增加索引并保留选择队列和词条`() {
        val name = "reading-upgrade-${UUID.randomUUID()}.db"
        withStore(name) { store ->
            store.learn("25", "北京", pinyin = "bei jing")
            store.enqueueReport(PendingReport("preserved", "chat_messages", "{}"), listOf("https://online"))
            tables.forEach { store.writableDatabase.execSQL("DROP INDEX IF EXISTS ${it}_reading") }
            store.writableDatabase.version = 12
        }
        withStore(name) { store ->
            assertEquals(13, store.writableDatabase.version)
            assertReadingIndexes(store.writableDatabase)
            assertTrue(store.personalWords("25").any { it.text == "北京" })
            assertEquals(1, store.learned("25").single().count.toInt())
            assertEquals(listOf("preserved"), store.pendingReports("https://online").map { it.id })
        }
        context.deleteDatabase(name)
    }

    @Test fun `范围简拼保留数字字母全拼禁词和手工优先语义`() {
        val name = "reading-candidates-${UUID.randomUUID()}.db"
        withStore(name) { store ->
            store.rememberWord("北京", "bei jing", "selection")
            store.rememberWord("不知道", "bu zhi dao", "selection")
            store.rememberWord("南京", "nan jing", "selection")
            store.rememberWord("上海", "shang hai", "selection")
            store.writableDatabase.execSQL("INSERT INTO dictionary_added_word VALUES('不着急','bu zhao ji','28942654',1)")
            store.writableDatabase.execSQL("INSERT INTO dictionary_remote_word VALUES('other','美国','mei guo','634486','selection')")
            assertEquals(setOf("北京"), store.personalWords("25").map { it.text }.toSet())
            assertEquals(setOf("北京"), store.personalWords("bj").map { it.text }.toSet())
            assertEquals(setOf("不知道"), store.personalWords("293").map { it.text }.toSet())
            assertEquals(setOf("不知道"), store.personalWords("bzd").map { it.text }.toSet())
            assertEquals(setOf("不着急"), store.personalWords("bzj", preferredOnly = true).map { it.text }.toSet())
            assertTrue(store.personalWords("bj", preferredOnly = true).isEmpty())
            assertTrue(store.personalWords("2345").any { it.text == "北京" })
            assertTrue(store.personalWords("beij").any { it.text == "北京" })
            assertTrue(store.personalWords("74").any { it.text == "上海" })
            assertEquals(setOf("美国"), store.personalWords("64").map { it.text }.toSet())
            assertEquals(setOf("美国"), store.personalWords("mg").map { it.text }.toSet())
            store.writableDatabase.execSQL("INSERT INTO dictionary_policy VALUES('北京','disabled')")
            assertFalse(store.personalWords("25").any { it.text == "北京" })
            assertFalse(store.personalWords("bj").any { it.text == "北京" })
        }
        context.deleteDatabase(name)
    }
}
