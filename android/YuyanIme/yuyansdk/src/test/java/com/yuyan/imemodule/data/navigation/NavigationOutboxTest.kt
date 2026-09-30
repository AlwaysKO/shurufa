package com.yuyan.imemodule.data.navigation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NavigationOutboxTest {
    @get:Rule val folder = TemporaryFolder()
    private fun record() = NavigationRecord(java.util.UUID.randomUUID().toString(), NavigationRoute("amap", "家", "公司"), byteArrayOf(1,2,3), 1_000, 2_000)
    @Test fun processRestartRetainsPayloadAndWrongReceiptCannotRemoveIt() = runBlocking {
        val dir = folder.newFolder(); val r = record()
        assertTrue(NavigationOutbox(dir).enqueue(r))
        var now = System.currentTimeMillis() + 1000
        val recreated = NavigationOutbox(dir) { now }
        assertEquals(1, recreated.count())
        recreated.drain({ true }) { """{"ok":true,"id":"wrong","sha256":"wrong"}""" }
        assertEquals(1, recreated.count())
        now += 31_000
        recreated.drain({ true }) { payload ->
            val json = JSONObject(payload)
            assertEquals("公司", json.getString("destination"))
            JSONObject().put("ok", true).put("id", json.getString("id")).put("sha256", json.getString("sha256")).toString()
        }
        assertEquals(0, recreated.count())
    }
    @Test fun revokedConsentDuringPersistenceDoesNotCreatePendingRecord() {
        val store = NavigationOutbox(folder.newFolder())
        var checks = 0
        assertFalse(store.enqueue(record()) { ++checks < 3 })
        assertEquals(0, store.count())
    }
    @Test fun offlineDisabledOrFailedSendPreservesRecordAndDuplicateEnqueueIsOneFile() = runBlocking {
        val store = NavigationOutbox(folder.newFolder()); val r = record()
        assertTrue(store.enqueue(r)); assertTrue(store.enqueue(r)); assertEquals(1, store.count())
        store.drain({ false }) { fail("disabled cannot send"); null }
        store.drain({ true }) { null }
        assertEquals(1, store.count())
    }
    @Test fun failedOldRecordsDoNotBlockNewValidRecords() = runBlocking {
        val dir = folder.newFolder(); val store = NavigationOutbox(dir)
        val first = record(); val second = record(); val third = record()
        listOf(first, second, third).forEach { assertTrue(store.enqueue(it)) }
        java.io.File(dir, "${first.id}.json").setLastModified(100)
        java.io.File(dir, "${second.id}.json").setLastModified(200)
        java.io.File(dir, "${third.id}.json").setLastModified(300)
        var sentThird = false
        repeat(2) { store.drain({ true }) { payload ->
            val json = JSONObject(payload)
            if (json.getString("id") != third.id) null else {
                sentThird = true
                JSONObject().put("ok", true).put("id", third.id).put("sha256", json.getString("sha256")).toString()
            }
        } }
        assertTrue(sentThird)
        assertEquals(2, store.count())
    }
}
