package com.yuyan.imemodule.data.capture.net

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CaptureApiGamePauseTest {
    private fun withAsset(block: (PendingAssetEntity, File) -> Unit) {
        val dir = createTempDirectory("capture-game-pause-").toFile()
        val file = File(dir, "image.webp").apply { writeText("test-image") }
        val asset = PendingAssetEntity("a".repeat(64), file.path, "image/webp", null, 10, 10)
        try { block(asset, file) } finally { dir.deleteRecursively() }
    }

    @Test fun gameBecomingActiveAtAnyPreparationCheckpointPreventsAssetHandoff() = withAsset { asset, file ->
        for (pauseAt in 1..6) {
            var checkpoints = 0
            var handedOff = false
            val api = CaptureApi("http://localhost", "device", enqueue = { _, _ -> handedOff = true; true },
                backgroundAllowed = { ++checkpoints < pauseAt })
            assertFalse("pause at checkpoint $pauseAt", api.uploadAsset(asset))
            assertFalse("must not transfer after pause at checkpoint $pauseAt", handedOff)
            assertTrue(file.exists())
        }
    }

    @Test fun gateChangeBeforeDirectHttpPreventsExecutingRequest() = withAsset { asset, _ ->
        var httpCalls = 0
        var checkpoints = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            httpCalls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("{}".toResponseBody()).build()
        }.build()
        val api = CaptureApi("http://localhost", "device", http = http,
            backgroundAllowed = { ++checkpoints < 7 })
        assertFalse(api.uploadAsset(asset))
        assertEquals(0, httpCalls)
    }

    @Test fun unpausedUploadPreservesInjectedHandoffAndEncodedPayload() = withAsset { asset, _ ->
        var transferred: Pair<String, String>? = null
        val api = CaptureApi("http://localhost", "device", enqueue = { path, body -> transferred = path to body; true },
            backgroundAllowed = { true })
        assertTrue(api.uploadAsset(asset))
        assertEquals("/api/v1/mobile/chat/assets", transferred!!.first)
        val body = Json.parseToJsonElement(transferred!!.second).jsonObject
        assertEquals("dGVzdC1pbWFnZQ==", body.getValue("file_base64").jsonPrimitive.content)
        assertEquals(asset.sha256, body.getValue("sha256").jsonPrimitive.content)
    }

    @Test fun pausedMessageBatchDoesNotTransferOrPretendItWasUploaded() {
        var handedOff = false
        val payload = PendingMessageUploadPayload("device", buildJsonObject { put("id", "chat") },
            buildJsonObject { put("text", "hello") })
        val api = CaptureApi("http://localhost", "device", enqueue = { _, _ -> handedOff = true; true },
            backgroundAllowed = { false })
        assertFalse(api.uploadMessages(listOf(payload)))
        assertFalse(handedOff)
    }

    @Test fun midPreparationPauseRetainsRoomTaskWithoutPenaltyAndResumesLater() = withAsset { asset, file ->
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
            try {
                val dao = db.captureDao()
                dao.insertPendingAsset(asset)
                var allowed = true
                var checks = 0
                var pauseDuringPreparation = true
                var handoffs = 0
                val api = CaptureApi("http://localhost", "device", enqueue = { _, _ -> handoffs++; true },
                    backgroundAllowed = {
                        if (pauseDuringPreparation && ++checks == 3) allowed = false
                        allowed
                    })
                val uploader = CaptureUploader(dao, api, { file }, backgroundAllowed = { allowed })
                assertEquals(UploadRunResult(1, 0), uploader.runOnce(1000))
                assertNotNull(dao.findPendingAsset(asset.sha256))
                assertEquals(0, dao.findPendingAsset(asset.sha256)!!.attempts)
                assertEquals(0, handoffs)
                assertTrue(file.exists())
                assertEquals(0L, uploader.internalFailureCount.get())
                pauseDuringPreparation = false
                allowed = true
                assertEquals(UploadRunResult(1, 0), uploader.runOnce(1000))
                assertNull(dao.findPendingAsset(asset.sha256))
                assertEquals(1, handoffs)
            } finally { db.close() }
        }
    }
}
