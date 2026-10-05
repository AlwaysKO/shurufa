package com.yuyan.imemodule.data.navigation

import android.content.Context
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

internal object NavigationSync {
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor).build()
    fun cancel() { http.dispatcher.cancelAll() }
    fun outbox(context: Context) = NavigationOutbox(File(context.filesDir, "navigation-outbox"))

    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        if (!ImageUploadRuntime.isBackgroundWorkAllowed() || !NavigationSettings.enabled(context) || !mutex.tryLock()) return@withContext
        try {
            val target = ServerConfig.baseUrl
            val generation = NavigationSettings.generation.get()
            val allowed = { ImageUploadRuntime.isBackgroundWorkAllowed() && NavigationSettings.uploadAllowed(context, generation) }
            outbox(context).drain({ allowed() && ImageUploadRuntime.canUploadScreenshot(context, target) }) { payload ->
                val permit = ImageUploadRuntime.tryStartImage(context, target, payload.toByteArray(Charsets.UTF_8).size.toLong())
                    ?: return@drain null
                try {
                    if (!allowed()) return@drain null
                    val request = Request.Builder().url("$target/api/v1/mobile/navigation-records")
                        .header("X-Device-Id", DataCollector.deviceId(context))
                        .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    val call = ImageUploadRuntime.prepareChatCall(context, target, http, request, allowed) ?: return@drain null
                    try { call.execute().use { if (it.isSuccessful) it.body?.string() else null } }
                    finally { ImageUploadRuntime.finishChatCall(call) }
                } finally { permit.close() }
            }
        } finally { mutex.unlock() }
    }
}
