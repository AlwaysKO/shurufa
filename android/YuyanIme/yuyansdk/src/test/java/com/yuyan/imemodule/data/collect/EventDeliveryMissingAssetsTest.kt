package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventDeliveryMissingAssetsTest {
    private val hash = "a".repeat(64)
    private fun asset(id: String = "asset", sha: String = hash) = PendingReport(id, "chat_asset", """{"sha256":"$sha","mime_type":"image/png","file_base64":"YQ=="}""")
    private fun message(id: String = "message", hashes: List<String> = listOf(hash)) = PendingReport(id, "chat_messages",
        """{"device_id":"device","conversation":{"platform":"wechat","identity_confidence":1},"messages":[{"message_type":"image","asset_sha256":[${hashes.joinToString(",") { "\"$it\"" }}]}]}""")
    private fun fixture(block: (LocalInputStore, MockWebServer, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "missing-assets-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val server = MockWebServer().apply { start() }
        try { block(store, server, server.url("/").toString().trimEnd('/')) }
        finally { server.shutdown(); store.close(); context.deleteDatabase(name) }
    }
    private fun hasPayload(store: LocalInputStore, id: String) = store.readableDatabase.rawQuery("SELECT 1 FROM pending_report WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    @Test fun imageAckRetainsRecoverablePayloadUntilAllReferencedMessagesAndTargetsAck() = fixture { store, _, target ->
        val other = "https://other.invalid"
        store.enqueueReport(asset(), listOf(target, other))
        store.enqueueReport(message(), listOf(target, other))
        store.enqueueReport(message("later"), listOf(target))
        store.acknowledgeReports(target, listOf("asset")); store.acknowledgeReports(other, listOf("asset"))
        assertTrue(hasPayload(store, "asset"))
        store.acknowledgeReports(target, listOf("message", "later"))
        assertTrue(hasPayload(store, "asset"))
        store.acknowledgeReports(other, listOf("message"))
        assertFalse(hasPayload(store, "asset"))
        assertFalse(store.hasPendingUploads())
    }

    @Test fun missingImage409RequeuesSameTargetImageThenRetriesMessageWithoutReregistering() = fixture { store, server, target ->
        store.enqueueReport(asset(), listOf(target)); store.enqueueReport(message(), listOf(target))
        store.acknowledgeReports(target, listOf("asset"))
        var messages = 0
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                if (request.path!!.endsWith("messages/batch") && messages++ == 0) return MockResponse().setResponseCode(409).setBody("""{"ok":false,"missingAssets":["$hash"]}""")
                return MockResponse().setBody("""{"ok":true,"sha256":"$hash"}""")
            }
        }
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}")
        assertFalse(sender.flush(target))
        assertEquals(listOf("asset"), store.pendingReports(target).map { it.id })
        assertTrue(hasPayload(store, "message"))
        assertTrue(sender.drain(target))
        assertEquals(listOf("/api/v1/mobile/device", "/api/v1/mobile/chat/messages/batch", "/api/v1/mobile/chat/assets", "/api/v1/mobile/chat/messages/batch"), paths)
        assertFalse(store.hasPendingUploads()); assertFalse(hasPayload(store, "asset"))
    }

    @Test fun unknownForeignOrOversizedMissingListsNeverRequeueUnrelatedImagesOrAckMessage() = fixture { store, server, target ->
        val foreign = "b".repeat(64)
        store.enqueueReport(asset(), listOf(target)); store.enqueueReport(asset("foreign", foreign), listOf(target))
        store.enqueueReport(message(), listOf(target)); store.acknowledgeReports(target, listOf("asset", "foreign"))
        var missing = "[\"$foreign\"]"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path!!.endsWith("messages/batch")) MockResponse().setResponseCode(409).setBody("""{"ok":false,"missingAssets":$missing}""") else MockResponse().setBody("""{"ok":true}""")
        }
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}")
        for (value in listOf("[\"$foreign\"]", "[\"invalid\"]", "[${List(2000) { "\"$hash\"" }.joinToString(",")}]")) {
            missing = value; assertFalse(sender.flush(target))
            assertEquals(listOf("message"), store.pendingReports(target).map { it.id })
        }
        assertTrue(hasPayload(store, "message"))
    }

    @Test fun atomicHandoffReusesRetainedImageAndRollsBackWhenMessageInsertFails() = fixture { store, _, target ->
        store.enqueueReport(asset(), listOf(target)); store.acknowledgeReports(target, listOf("asset"))
        store.enqueueChatBatch(listOf(asset("duplicate"), message()), listOf(target))
        assertFalse(hasPayload(store, "duplicate")); assertTrue(hasPayload(store, "asset"))
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_report WHERE kind='chat_asset'", null).use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
        store.writableDatabase.execSQL("CREATE TRIGGER fail_message BEFORE INSERT ON pending_report WHEN NEW.id='fails' BEGIN SELECT RAISE(ABORT,'injected storage failure'); END")
        assertThrows(Exception::class.java) { store.enqueueChatBatch(listOf(asset("new-image", "b".repeat(64)), message("fails")), listOf(target)) }
        assertFalse(hasPayload(store, "new-image")); assertFalse(hasPayload(store, "fails"))
    }

    @Test fun unknownOldMessageDependenciesPreventImageGcAndOtherTargetCannotRecoverIt() = fixture { store, _, target ->
        store.enqueueReport(asset(), listOf(target)); store.enqueueReport(message(), listOf(target))
        store.enqueueReport(PendingReport("unknown", "chat_messages", "broken"), listOf(target))
        store.acknowledgeReports(target, listOf("asset", "message"))
        assertTrue(hasPayload(store,"asset"))
        val other = "https://other.invalid"
        store.enqueueReport(message("other"),listOf(other))
        assertEquals(0,store.requeueMissingChatAssets(other,"other",setOf(hash)))
    }

    @Test fun versionElevenMigrationRetainsPayloadAndRestoresTargetOwnership() = fixture { store, _, target ->
        store.enqueueReport(asset(), listOf(target)); store.enqueueReport(message(), listOf(target))
        val db = store.writableDatabase
        db.execSQL("DROP TABLE report_image_target")
        store.onUpgrade(db,11,12)
        store.acknowledgeReports(target,listOf("asset"))
        assertEquals(1,store.requeueMissingChatAssets(target,"message",setOf(hash)))
    }

    @Test fun localExpiryCannotDiscardImageDependenciesBeforeEveryTargetAcknowledges() = fixture { store, _, target ->
        val other = "https://other.invalid"
        store.enqueueReport(asset(),listOf(target,other)); store.enqueueReport(message(),listOf(target,other))
        store.enqueueReport(PendingReport("unknown","chat_messages","broken"),listOf(target,other))
        store.acknowledgeReports(target,listOf("asset","message","unknown"),target)
        store.pruneExpiredLocalChatReports(target,0)
        assertTrue(hasPayload(store,"asset")); assertTrue(hasPayload(store,"message")); assertTrue(hasPayload(store,"unknown"))
        assertEquals(1,store.requeueMissingChatAssets(other,"message",setOf(hash)))
        assertEquals(listOf("asset"),store.pendingReports(other).map { it.id })
    }
}
