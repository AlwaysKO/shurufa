package com.yuyan.imemodule.data.capture.net

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.db.CaptureDao
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.sha256
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CaptureUploaderTest {
    private lateinit var database: CaptureDatabase
    private lateinit var dao: CaptureDao
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.captureDao()
        server = MockWebServer().apply { start() }
        tempDir = createTempDirectory("capture-uploader-").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        tempDir.deleteRecursively()
    }

    private fun pendingNotification(id: String, requiredHash: String? = null): PendingMessageEntity {
        val original=pendingMessage(id,requiredHash)
        val decoded=CaptureApi("http://localhost",DEVICE_ID).decodeMessagePayload(original.payloadJson)
        return original.copy(payloadJson=Json.encodeToString(decoded.copy(
            conversation=JsonObject(decoded.conversation + ("identity_confidence" to JsonPrimitive(.55))),
            message=JsonObject(decoded.message + ("metadata" to buildJsonObject {
                put("capture_source","notification");put("conversation_identity_status","pending")
                put("identity_unavailable","true")
            })),
        )))
    }

    @Test fun unconfirmedTextInOldRoomQueueEndsLocallyWithoutNetworkOrLosingSeenRecord() = runBlocking {
        val message=pendingNotification("noise")
        dao.insertSeen(com.yuyan.imemodule.data.capture.db.SeenMessageEntity(message.fingerprint,100))
        dao.insertPendingMessage(message)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        assertEquals(UploadRunResult(1,0),uploader().runOnce(1000))
        assertEquals(0,server.requestCount)
        assertFalse(dao.hasPendingMessage(message.id))
        assertEquals(message.fingerprint,dao.findSeen(message.fingerprint)?.fingerprint)
    }

    @Test fun unconfirmedTextWithRoomAssetDependencyWaitsAndIsNeverDiscarded() = runBlocking {
        val asset=pendingAsset().copy(nextRetryAt=Long.MAX_VALUE)
        val message=pendingNotification("with-dependency",asset.sha256)
        dao.insertPendingAsset(asset);dao.insertPendingMessage(message)
        assertEquals(UploadRunResult(0,0),uploader().runOnce(1000))
        assertTrue(dao.hasPendingMessage(message.id));assertTrue(File(asset.localPath).exists())
        assertEquals(0,server.requestCount)
        dao.deletePendingAsset(asset.sha256) // 依赖已转入普通图片待传队列。
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        assertEquals(UploadRunResult(1,0),uploader().runOnce(2000))
        assertEquals(1,server.requestCount)
        val wire=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(asset.sha256))),wire["messages"]!!.jsonArray.single().jsonObject["asset_sha256"])
    }

    @Test fun filteredRoomBatchDoesNotCreateAGenericChatReport() {
        var enqueued=0
        val api=CaptureApi("http://localhost",DEVICE_ID,enqueue={_,_->enqueued++;true})
        assertTrue(api.uploadMessages(listOf(api.decodeMessagePayload(pendingNotification("noise").payloadJson))))
        assertEquals(0,enqueued)
        assertTrue(api.uploadMessages(listOf(api.decodeMessagePayload(pendingMessage("confirmed").payloadJson))))
        assertEquals(1,enqueued)
    }

    @Test fun malformedRoomDependenciesNeverBecomeDiscardableEmptyResourceLists() = runBlocking {
        for ((index,dependencies) in listOf("broken json","{}","null").withIndex()) {
            val message=pendingNotification("unknown-dependencies-$index").copy(requiredAssetHashesJson=dependencies)
            dao.insertPendingMessage(message)
            assertEquals(UploadRunResult(0,0),uploader().runOnce(1000))
            assertTrue(dao.hasPendingMessage(message.id))
        }
        assertEquals(0,server.requestCount)
    }

    @Test
    fun pendingCheckIncludesPausedAndBackoffTasksWithoutDecodingPayload() = runBlocking {
        assertFalse(dao.hasPendingWork())
        val message = pendingMessage("paused").copy(payloadJson = "invalid json", nextRetryAt = Long.MAX_VALUE)
        dao.insertPendingMessage(message)
        val paused = CaptureUploader(dao, CaptureApi(server.url("/").toString(), DEVICE_ID),
            assetFile = { error("paused work must not read files") }, backgroundAllowed = { false })
        assertEquals(UploadRunResult(0, 0), paused.runOnce(now = 1_000))
        assertTrue(dao.hasPendingWork())
        dao.confirmMessageUploaded(message.id)
        assertFalse(dao.hasPendingWork())
        val asset = pendingAsset().copy(nextRetryAt = Long.MAX_VALUE)
        dao.insertPendingAsset(asset)
        assertTrue(dao.hasPendingWork())
        dao.deletePendingAsset(asset.sha256)
        assertFalse(dao.hasPendingWork())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun uploadsAssetBeforeMessageAndCleansOutboxAndTemporaryFile() = runBlocking {
        val asset = pendingAsset()
        val message = pendingMessage("message-1", asset.sha256)
        dao.insertPendingAsset(asset)
        dao.insertPendingMessage(message)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"duplicated":false}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"inserted":1}"""))

        uploader().runOnce(now = 1_000)

        val assetRequest = server.takeRequest()
        val messageRequest = server.takeRequest()
        assertEquals("/api/v1/mobile/chat/assets", assetRequest.path)
        assertEquals("/api/v1/mobile/chat/messages/batch", messageRequest.path)
        assertEquals(DEVICE_ID, assetRequest.getHeader("X-Device-Id"))
        assertEquals(DEVICE_ID, messageRequest.getHeader("X-Device-Id"))
        assertFalse(dao.hasPendingMessage(message.id))
        assertEquals(null, dao.findPendingAsset(asset.sha256))
        assertFalse(File(asset.localPath).exists())
    }

    @Test fun durableQueueHandoffIsNotARemoteMessageAckAndCannotRemoveOriginal() = runBlocking {
        val asset = pendingAsset()
        dao.insertPendingAsset(asset); dao.insertPendingMessage(pendingMessage("queued", asset.sha256))
        val queued = mutableListOf<String>()
        val api = CaptureApi(server.url("/").toString(), DEVICE_ID, enqueue = { path, _ -> queued += path; true })
        CaptureUploader(dao, api, assetFile = { File(tempDir, it) }).runOnce(1000)
        assertEquals(0, server.requestCount)
        assertEquals(2, queued.size)
        assertTrue("a durable handoff is not a remote acknowledgement", File(asset.localPath).isFile)
    }

    @Test fun atomicHandoffIncludesActualImageAndKeepsLaterRoomReferenceAndOriginal() = runBlocking {
        val asset = pendingAsset()
        dao.insertPendingAsset(asset)
        dao.insertPendingMessage(pendingMessage("first",asset.sha256))
        dao.insertPendingMessage(pendingMessage("later",asset.sha256).copy(nextRetryAt=9000))
        val batches = mutableListOf<List<Pair<String,String>>>()
        val api = CaptureApi(server.url("/").toString(), DEVICE_ID, enqueueBatch = { batches += listOf(it.toList()); true })
        val worker = CaptureUploader(dao,api,assetFile={File(tempDir,it)})
        assertEquals(UploadRunResult(1,0),worker.runOnce(1000))
        assertFalse(dao.hasPendingMessage("first")); assertTrue(dao.hasPendingMessage("later"))
        assertTrue(dao.findPendingAsset(asset.sha256) != null); assertTrue(File(asset.localPath).isFile)
        assertEquals(listOf("/api/v1/mobile/chat/assets","/api/v1/mobile/chat/messages/batch"),batches.single().map { it.first })
        assertTrue(batches.single()[0].second.contains("YXNzZXQ="))
        assertEquals(UploadRunResult(1,0),worker.runOnce(9000))
        assertEquals(2,batches.size); assertTrue(File(asset.localPath).isFile)
        assertEquals(0,server.requestCount)
    }

    @Test fun missingOriginalOrFailedAtomicCommitNeverConfirmsRoomMessage() = runBlocking {
        val asset = pendingAsset()
        dao.insertPendingAsset(asset);dao.insertPendingMessage(pendingMessage("pending",asset.sha256))
        var batches = 0
        val api = CaptureApi(server.url("/").toString(),DEVICE_ID,enqueueBatch={ batches++;false })
        val worker = CaptureUploader(dao,api,assetFile={File(tempDir,it)})
        assertEquals(UploadRunResult(1,1),worker.runOnce(1000))
        assertTrue(dao.hasPendingMessage("pending"));assertTrue(File(asset.localPath).isFile)
        File(asset.localPath).delete()
        assertEquals(UploadRunResult(1,1),worker.runOnce(40000))
        assertEquals(1,batches);assertTrue(dao.hasPendingMessage("pending"))
        assertTrue(dao.findPendingAsset(asset.sha256)!=null)
    }

    @Test fun atomicHandoffBoundsReadsAndDefersMalformedDependenciesWithoutStarvingLaterMessages() = runBlocking {
        for (i in 0..2) dao.insertPendingMessage(pendingMessage("bad-$i").copy(requiredAssetHashesJson="broken"))
        dao.insertPendingMessage(pendingMessage("valid"))
        var batches=0
        val worker=CaptureUploader(dao,CaptureApi(server.url("/").toString(),DEVICE_ID,enqueueBatch={batches++;true}),assetFile={File(tempDir,it)})
        assertEquals(UploadRunResult(2,2),worker.runOnce(1000))
        assertEquals(UploadRunResult(2,1),worker.runOnce(1000))
        assertEquals(1,batches);assertFalse(dao.hasPendingMessage("valid"));assertTrue(dao.hasPendingMessage("bad-0"))
    }

    @Test fun oversizedAtomicImageIsNotReadOrHandedOff() = runBlocking {
        val asset=pendingAsset()
        java.io.RandomAccessFile(File(asset.localPath),"rw").use { it.setLength(11L*1024*1024) }
        dao.insertPendingAsset(asset);dao.insertPendingMessage(pendingMessage("large",asset.sha256))
        val api=CaptureApi(server.url("/").toString(),DEVICE_ID,enqueueBatch={error("oversized image must stay local")})
        assertEquals(UploadRunResult(1,1),CaptureUploader(dao,api,assetFile={File(tempDir,it)}).runOnce(1000))
        assertTrue(dao.hasPendingMessage("large"));assertTrue(File(asset.localPath).isFile)
    }

    @Test fun malformedWireAssetDependenciesNeverBecomeEmptySuccessfulHandoffs() = runBlocking {
        val malformed = listOf(JsonObject(emptyMap()), JsonPrimitive("bad"), JsonArray(listOf(JsonPrimitive(123))))
        val api=CaptureApi(server.url("/").toString(),DEVICE_ID,enqueueBatch={error("malformed dependency must not hand off")})
        for ((index, value) in malformed.withIndex()) {
            val message=pendingMessage("malformed-$index")
            val payload=api.decodeMessagePayload(message.payloadJson)
            dao.insertPendingMessage(message.copy(payloadJson=Json.encodeToString(payload.copy(message=JsonObject(payload.message + ("asset_sha256" to value))))))
            assertEquals(UploadRunResult(1,1),CaptureUploader(dao,api,assetFile={File(tempDir,it)}).runOnce(1000))
            assertTrue(dao.hasPendingMessage(message.id))
        }
    }

    @Test
    fun duplicatedAssetResponseIsStillSuccessful() = runBlocking {
        val asset = pendingAsset()
        val message = pendingMessage("message-duplicated", asset.sha256)
        dao.insertPendingAsset(asset)
        dao.insertPendingMessage(message)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"duplicated":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"duplicated":0}"""))

        uploader().runOnce(now = 1_000)

        assertFalse(dao.hasPendingMessage(message.id))
        assertEquals(null, dao.findPendingAsset(asset.sha256))
    }

    @Test
    fun httpFailuresKeepSameOutboxIdentityAndUseBackoff() = runBlocking {
        listOf(429, 500).forEachIndexed { index, code ->
            val message = pendingMessage("http-$code")
            dao.insertPendingMessage(message)
            server.enqueue(MockResponse().setResponseCode(code))
            val now = 10_000L

            uploader().runOnce(now)
            uploader().runOnce(now)

            val retained = dao.readyMessages(Long.MAX_VALUE, 200).first { it.id == message.id }
            assertEquals(message.id, retained.id)
            assertEquals(message.fingerprint, retained.fingerprint)
            assertEquals(1, retained.attempts)
            assertEquals(now + 30_000L, retained.nextRetryAt)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun networkDisconnectKeepsOutbox() = runBlocking {
        val message = pendingMessage("disconnected")
        dao.insertPendingMessage(message)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        uploader().runOnce(now = 5_000)

        assertTrue(dao.hasPendingMessage(message.id))
        val retained = dao.readyMessages(Long.MAX_VALUE, 200).single()
        assertEquals(1, retained.attempts)
        assertEquals(35_000L, retained.nextRetryAt)
    }

    @Test
    fun retryBackoffIsCappedAtThirtyMinutes() {
        assertEquals(30_000L, retryDelayMillis(1))
        assertEquals(120_000L, retryDelayMillis(2))
        assertEquals(600_000L, retryDelayMillis(3))
        assertEquals(1_800_000L, retryDelayMillis(4))
        assertEquals(1_800_000L, retryDelayMillis(99))
    }

    @Test
    fun uploadsAtMostTwentyMessagesPerBatch() = runBlocking {
        repeat(201) { index -> dao.insertPendingMessage(pendingMessage("batch-$index")) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"inserted":200}"""))

        uploader().runOnce(now = 1_000)

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(20, body.getValue("messages").jsonArray.size)
        assertEquals(181, dao.readyMessages(Long.MAX_VALUE, 500).size)
    }

    private fun uploader() = CaptureUploader(
        dao = dao,
        api = CaptureApi(server.url("/").toString(), DEVICE_ID, OkHttpClient()),
        assetFile = { hash -> File(tempDir, hash) },
    )

    private fun pendingAsset(): PendingAssetEntity {
        val bytes = "asset".toByteArray()
        val hash = sha256(bytes)
        val file = File(tempDir, hash).apply { writeBytes(bytes) }
        return PendingAssetEntity(hash, file.absolutePath, "image/webp", null, 10, 10)
    }

    private fun pendingMessage(id: String, requiredHash: String? = null): PendingMessageEntity {
        val fingerprint = sha256(id.toByteArray())
        val payload = PendingMessageUploadPayload(
            deviceId = DEVICE_ID,
            conversation = buildJsonObject {
                put("platform", "wechat")
                put("account_key", "account")
                put("external_key", "peer")
                put("conversation_type", "direct")
                put("identity_confidence", 0.95)
            },
            message = buildJsonObject {
                put("id", id)
                put("fingerprint", fingerprint)
                put("content_fingerprint", "f".repeat(64))
                put("sender_key", "peer")
                put("direction", "incoming")
                put("message_type", "text")
                put("captured_at", "2026-08-20T00:00:00.000Z")
            },
        )
        return PendingMessageEntity(
            id = id,
            fingerprint = fingerprint,
            conversationKey = "wechat|account|peer",
            payloadJson = Json.encodeToString(payload),
            requiredAssetHashesJson = requiredHash?.let { "[\"$it\"]" } ?: "[]",
        )
    }

    private companion object {
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000001"
    }
}
