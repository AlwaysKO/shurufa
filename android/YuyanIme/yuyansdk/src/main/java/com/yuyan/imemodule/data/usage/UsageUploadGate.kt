package com.yuyan.imemodule.data.usage

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Own prepared calls, not just OkHttp's already-executing dispatcher calls. */
internal class UsageUploadGate(private val allowed: () -> Boolean) {
    private val calls=mutableSetOf<Call>()
    @Synchronized fun prepare(http: OkHttpClient, request: Request): Call? {
        if(!allowed()) return null
        return http.newCall(request).also { calls.add(it) }
    }
    @Synchronized fun finish(call: Call) { calls.remove(call) }
    @Synchronized fun cancel() { calls.forEach { it.cancel() }; calls.clear() }
}
