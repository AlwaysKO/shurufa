package com.yuyan.imemodule.data.completion

import android.content.Context
import android.database.MatrixCursor
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import com.yuyan.imemodule.application.Launcher
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemDictionaryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun initAssets() {
        Launcher::class.java.getDeclaredField("context").apply { isAccessible = true; set(Launcher.instance, context) }
    }
    @Test fun `任意英文快捷码不能冒充旧词的拼音`() {
        val prefs = context.getSharedPreferences("migration-shortcut", 0).apply { edit().clear().commit() }
        context.deleteDatabase("migration-shortcut.db")
        val db = LocalInputStore(context, "migration-shortcut.db")
        try {
            val cursor = MatrixCursor(arrayOf("word", "shortcut")).apply { addRow(arrayOf("中国", "hello world")) }
            assertEquals(1, SystemDictionaryMigration(prefs, db, { cursor }, { emptyMap() }).run().imported)
            assertTrue(db.personalWords(T9Lexicon.digits("helloworld")).isEmpty())
        } finally { db.close() }
    }

    @Test fun `自动导入幂等且不伪造真实选择次数或上传`() {
        val prefs = context.getSharedPreferences("migration-test", 0).apply { edit().clear().commit() }
        context.deleteDatabase("migration-test.db")
        val db = LocalInputStore(context, "migration-test.db")
        try {
            val cursor = MatrixCursor(arrayOf("word", "shortcut")).apply {
                addRow(arrayOf("真的吗", "zhen de ma"))
                addRow(arrayOf("玩漂流", null))
                addRow(arrayOf("password123", null))
            }
            var queries = 0
            val migration = SystemDictionaryMigration(prefs, db, { queries++; cursor }, { emptyMap() })
            assertEquals("complete", migration.run().status)
            assertEquals(2, migration.run().imported)
            assertEquals(1, queries)
            assertTrue(cursor.isClosed)
            assertEquals("真的吗", db.personalWords("94363362").single().text)
            assertTrue(db.learned("94363362").isEmpty())
            assertTrue(db.reportTargets().isEmpty())
        } finally { db.close() }
    }
    @Test fun `权限拒绝和缺少词典不冒充成功`() {
        val prefs = context.getSharedPreferences("migration-denied", 0).apply { edit().clear().commit() }
        val db = LocalInputStore(context, "migration-denied.db")
        try {
            assertEquals("permission_denied", SystemDictionaryMigration(prefs, db,
                { throw SecurityException("private") }, { emptyMap() }).run().status)
            prefs.edit().clear().commit()
            assertEquals("unavailable", SystemDictionaryMigration(prefs, db, { null }, { emptyMap() }).run().status)
        } finally { db.close() }
    }
}
