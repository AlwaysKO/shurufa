package com.yuyan.imemodule.expression.send

import android.content.Context
import android.content.SharedPreferences
import com.yuyan.imemodule.data.collect.BackgroundRefreshRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 一次写入完整验证过的文档；切后台主地址不会复用另一来源的规则。 */
class ExpressionDeliveryStore(private val preferences: SharedPreferences, private val authority: String) {
    private val key = "config:$authority"
    fun current(): ExpressionDeliveryPolicy = synchronized(lock) {
        preferences.getString(key, null)?.let(ExpressionDeliveryPolicy::parse) ?: ExpressionDeliveryPolicy.defaults()
    }

    fun accept(raw: String): Boolean {
        val candidate = ExpressionDeliveryPolicy.parse(raw) ?: return false
        return synchronized(lock) {
            // 回滚由服务端产生更高revision，迟到响应不能覆盖新配置。
            if (candidate.revision <= current().revision) false
            else preferences.edit().putString(key, raw).commit()
        }
    }

    suspend fun fetch(client: OkHttpClient, deviceId: String, allowed: () -> Boolean = GameWorkRuntime::isBackgroundAllowed): Boolean = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext false
        try {
            val request = Request.Builder().url("$authority/api/v1/mobile/expression-delivery")
                .header("X-Device-Id", deviceId).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || !allowed()) return@withContext false
                val body = response.body ?: return@withContext false
                if (body.contentLength() > ExpressionDeliveryPolicy.MAX_BYTES) return@withContext false
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (!allowed()) return@withContext false
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (bytes.size() + read > ExpressionDeliveryPolicy.MAX_BYTES) return@withContext false
                        bytes.write(buffer, 0, read)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!allowed()) return@withContext false
                accept(bytes.toString("UTF-8"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false // 网络、版本或格式错误不替换最后有效配置。
        }
    }

    private companion object { val lock = Any() }
}

object ExpressionDeliverySettings {
    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor).connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }

    suspend fun current(context: Context): ExpressionDeliveryPolicy = withContext(Dispatchers.IO) {
        store(context.applicationContext, ServerConfig.baseUrl).current()
    }

    /** 由真实输入法窗口生命周期调用，不在点击发送时等待网络。 */
    fun refresh(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) { BackgroundRefreshRuntime.request(app) }
    }

    internal suspend fun refreshInBatch(context: Context) {
        val app = context.applicationContext
        val authority = ServerConfig.baseUrl
        val allowed = { ImageUploadRuntime.hasValidatedNetwork(app) && ImageUploadRuntime.isBackgroundWorkAllowed() && ServerConfig.baseUrl == authority }
        if (allowed()) store(app, authority).fetch(client, DataCollector.deviceId(app), allowed)
    }

    private fun store(context: Context, authority: String) = ExpressionDeliveryStore(
        context.getSharedPreferences("expression_delivery", Context.MODE_PRIVATE), authority,
    )
}
