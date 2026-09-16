package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommittedEditPersistenceTest {
    @Test fun 输入删除快照重开数据库后完整保留双端独立确认() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "edits-${UUID.randomUUID()}.db"
        var store = LocalInputStore(context, name)
        val first = MobileEvent("first", "device", "commit", text = "八点见",
            occurredAt = "2026-09-16T00:00:00Z", sessionId = UUID.randomUUID().toString(),
            sequenceNo = 1, textBefore = "", textAfter = "八点见",
            metadata = buildJsonObject { put("edit_protocol", 1); put("snapshot_complete", true) })
        val deleted = first.copy(id = "delete", eventType = "delete", sequenceNo = 2,
            text = "八", textBefore = "八点见", textAfter = "点见")
        try {
            store.enqueue(first, listOf("local", "online"))
            store.enqueue(deleted, listOf("local", "online"))
            store.acknowledge("online", listOf(first.id, deleted.id))
            store.close()
            store = LocalInputStore(context, name)
            assertEquals(listOf(first, deleted), store.pending("local"))
            assertTrue(store.pending("online").isEmpty())
            val legacy = Json.decodeFromString(MobileEvent.serializer(),
                """{"id":"old","device_id":"device","event_type":"commit","text":"旧","occurred_at":"2026-09-16"}""")
            assertNull(legacy.textBefore)
            assertNull(legacy.sessionId)
        } finally {
            store.close(); context.deleteDatabase(name)
        }
    }
}
