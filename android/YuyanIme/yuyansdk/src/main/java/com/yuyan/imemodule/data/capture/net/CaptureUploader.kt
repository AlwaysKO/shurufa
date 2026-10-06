package com.yuyan.imemodule.data.capture.net

import android.content.Context
import com.yuyan.imemodule.data.capture.db.CaptureDao
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
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
    private val beginPreparation: () -> java.io.Closeable? = { java.io.Closeable {} },
    private val backgroundAllowed: () -> Boolean = { true },
) {
    val internalFailureCount = AtomicLong(0)

    suspend fun runOnce(now: Long = System.currentTimeMillis()): UploadRunResult {
        var processed = 0
        var failures = 0
        if (!backgroundAllowed()) return UploadRunResult(0, 0)

        for (asset in dao.dueAssets(now, MAX_ASSET_BATCH)) {
            if (!backgroundAllowed()) break
            // 在 CaptureApi 读取文件及 Base64 编码前领取共享许可；暂停不记失败。
            val preparation = beginPreparation() ?: continue
            processed += 1
            val uploaded = try {
                api.uploadAsset(asset)
            } catch (_: Exception) {
                false
            } finally {
                preparation.close()
            }
            if (uploaded) {
                dao.deletePendingAsset(asset.sha256)
            } else {
                if (!backgroundAllowed()) break
                failures += 1
                markAssetFailed(asset, now)
            }
        }

        val decoded = mutableListOf<Pair<PendingMessageEntity, PendingMessageUploadPayload>>()
        if (!backgroundAllowed()) return UploadRunResult(processed, failures)
        for (message in dao.readyMessages(now, MAX_MESSAGE_BATCH)) {
            if (!backgroundAllowed()) break
            try {
                val payload = api.decodeMessagePayload(message.payloadJson)
                // 旧队列也可能只在依赖列保留图片引用；合并真实依赖，不能误当无图文字结束。
                val dependencies = requiredAssets(message)
                val existing = payload.message["asset_sha256"]
                val assetReferences = (existing as? JsonArray)?.toList().orEmpty()
                val restored = if (dependencies.isNotEmpty() && (existing == null || existing == JsonNull || existing is JsonArray)) payload.copy(
                    message = JsonObject(payload.message + ("asset_sha256" to JsonArray((assetReferences + dependencies.map(::JsonPrimitive)).distinct())))
                ) else payload
                decoded += message to restored
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
            if (!backgroundAllowed()) break
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
                if (!backgroundAllowed()) break
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
        private const val MAX_ASSET_BATCH = 2
        private const val MAX_MESSAGE_BATCH = 20
        private val startLock = Any()
        private var uploadJob: Job? = null
        private val wakeSignal = CaptureWorkSignal()

        fun wake() {
            wakeSignal.wake()
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            synchronized(startLock) {
                if (uploadJob?.isActive == true) return
                ServerConfig.init(appContext)
                val database = CaptureDatabase.create(appContext)
                val uploader = CaptureUploader(
                    dao = database.captureDao(),
                    api = CaptureApi(ServerConfig.baseUrl, DataCollector.deviceId(appContext), enqueue = { path, body ->
                        DataCollector.enqueueRawReport(appContext, path, body)
                    }, backgroundAllowed = ImageUploadRuntime::isBackgroundWorkAllowed),
                    assetFile = { hash -> File(appContext.cacheDir, "chat-capture/$hash") },
                    beginPreparation = ImageUploadRuntime::beginPreparation,
                    backgroundAllowed = ImageUploadRuntime::isBackgroundWorkAllowed,
                )
                uploadJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    while (isActive) {
                        val result = try {
                            if (CollectionConsent.enabled(appContext)) uploader.runOnce()
                            else UploadRunResult(0, 0)
                        } catch (_: Exception) {
                            uploader.internalFailureCount.incrementAndGet()
                            UploadRunResult(processed = 0, failures = 1)
                        }
                        val pending = try { database.captureDao().hasPendingWork() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { true }
                        wakeSignal.awaitNext(
                            processed = result.processed > 0 && ImageUploadRuntime.isBackgroundWorkAllowed(),
                            hasPending = pending,
                        )
                    }
                }
            }
        }
    }
}
