package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExpressionSync(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val deviceId: String,
    initialCatalog: ExpressionCatalog,
    private val cache: ExpressionCache,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var catalog = initialCatalog
    private val json = Json { ignoreUnknownKeys = true }

    fun currentCatalog(): ExpressionCatalog = catalog

    suspend fun refreshCatalog(): ExpressionCatalog = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/mobile/expressions/catalog"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("version", catalog.document.version)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId)
                .build()
            client.newCall(request).awaitResponse().use { response ->
                if (response.code == 304) return@withContext catalog
                check(response.isSuccessful) { "catalog request failed: ${response.code}" }
                val remote = json.decodeFromString<ExpressionCatalogDocument>(
                    response.body?.string().orEmpty(),
                )
                catalog = catalog.merge(remote)
                catalog
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalog
        }
    }

    fun search(
        query: String,
        requestId: Long,
        acceptResponse: (Long) -> Boolean,
        onResult: (List<ExpressionAsset>) -> Unit,
    ): Job {
        return scope.launch {
            // UI 订阅方的本地结果、请求代次校验和远端结果都回到 scope 的调度器串行执行。
            onResult(catalog.search(query))
            val remoteResults = withContext(Dispatchers.IO) {
                try {
                val url = "$baseUrl/api/v1/mobile/expressions/recommend"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", query)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("X-Device-Id", deviceId)
                    .build()
                client.newCall(request).awaitResponse().use { response ->
                    check(response.isSuccessful) { "recommend request failed: ${response.code}" }
                    json.decodeFromString<RecommendationResponse>(
                        response.body?.string().orEmpty(),
                    ).results
                }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            remoteResults
                ?.takeIf { acceptResponse(requestId) }
                ?.let { results ->
                    ExpressionCatalog(
                        ExpressionCatalogDocument(
                            version = catalog.document.version,
                            templates = results,
                            emojiBases = emptyList(),
                            emojiCombinations = emptyList(),
                        ),
                    ).recommend(query)
                }
                ?.let(onResult)
        }
    }

    suspend fun download(
        version: String,
        relativePath: String,
        url: String,
        sha256: String,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId)
                .build()
            client.newCall(request).awaitResponse().use { response ->
                check(response.isSuccessful) { "asset request failed: ${response.code}" }
                val body = response.body ?: error("empty asset response")
                cache.writeVerified(version, relativePath, sha256, body.byteStream())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class RecommendationResponse(
        val results: List<ExpressionAsset>,
    )
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, unconsumedResponse, _ ->
                unconsumedResponse.close()
            }
        }
    })
}
