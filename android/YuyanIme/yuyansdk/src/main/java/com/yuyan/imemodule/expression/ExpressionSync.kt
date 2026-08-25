package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

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
        runCatching {
            val url = "$baseUrl/api/v1/mobile/expressions/catalog"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("version", catalog.document.version)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 304) return@runCatching catalog
                check(response.isSuccessful) { "catalog request failed: ${response.code}" }
                val remote = json.decodeFromString<ExpressionCatalogDocument>(
                    response.body?.string().orEmpty(),
                )
                catalog = catalog.merge(remote)
                catalog
            }
        }.getOrElse { catalog }
    }

    fun search(
        query: String,
        requestId: Long,
        acceptResponse: (Long) -> Boolean,
        onResult: (List<ExpressionAsset>) -> Unit,
    ): Job {
        onResult(catalog.search(query))
        return scope.launch(Dispatchers.IO) {
            runCatching {
                val url = "$baseUrl/api/v1/mobile/expressions/recommend"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", query)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("X-Device-Id", deviceId)
                    .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "recommend request failed: ${response.code}" }
                    json.decodeFromString<RecommendationResponse>(
                        response.body?.string().orEmpty(),
                    ).results
                }
            }.getOrNull()?.takeIf { acceptResponse(requestId) }?.let(onResult)
        }
    }

    suspend fun download(
        version: String,
        relativePath: String,
        url: String,
        sha256: String,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId)
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "asset request failed: ${response.code}" }
                val body = response.body ?: error("empty asset response")
                cache.writeVerified(version, relativePath, sha256, body.byteStream())
            }
        }.getOrNull()
    }

    @Serializable
    private data class RecommendationResponse(
        val results: List<ExpressionAsset>,
    )
}
