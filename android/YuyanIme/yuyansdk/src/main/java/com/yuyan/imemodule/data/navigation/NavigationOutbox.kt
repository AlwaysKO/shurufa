package com.yuyan.imemodule.data.navigation

import java.io.File
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

internal class NavigationOutbox(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    fun enqueue(record: NavigationRecord, allowed: () -> Boolean = { true }): Boolean = runCatching {
        if (!allowed()) return false
        require(record.id.matches(Regex("[a-f0-9-]{36}")))
        require(record.image.isNotEmpty() && record.image.size <= 3 * 1024 * 1024)
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "${record.id}.json")
        if (file.isFile) return true
        val payload = JSONObject().put("id", record.id).put("platform", record.route.platform)
            .put("origin", record.route.origin).put("destination", record.route.destination)
            .put("overview_at", record.overviewAt).put("started_at", record.startedAt)
            .put("mime_type", "image/webp").put("sha256", sha256(record.image))
            .put("file_base64", Base64.encodeToString(record.image, Base64.NO_WRAP)).toString()
        val atomic = AtomicFile(file)
        if (!allowed()) return false
        val stream = atomic.startWrite()
        try {
            stream.write(payload.toByteArray(Charsets.UTF_8))
            if (!allowed()) { atomic.failWrite(stream); return false }
            atomic.finishWrite(stream)
        }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        true
    }.getOrDefault(false)

    private fun files() = directory.listFiles { file -> file.name.matches(Regex("[a-f0-9-]{36}\\.json")) }.orEmpty().sortedBy { it.lastModified() }
    fun count(): Int = files().size

    suspend fun drain(allowed: () -> Boolean, send: suspend (String) -> String?) {
        for (file in files().filter { it.lastModified() <= now() }.take(2)) {
            if (!allowed()) return
            try {
                if (file.length() > 4 * 1024 * 1024 + 4096) continue
                val payload = AtomicFile(file).readFully().toString(Charsets.UTF_8)
                if (!allowed()) return
                val record = JSONObject(payload)
                val response = send(payload) ?: continue
                val receipt = JSONObject(response)
                if (receipt.optBoolean("ok") && receipt.optString("id") == record.getString("id") &&
                    receipt.optString("sha256") == record.getString("sha256")) AtomicFile(file).delete()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* 失败记录保留到下次重试。 */ }
            finally {
                // 持久退避让后续正常记录有机会发送，重启后也不会被坏记录堵住。
                if (file.exists()) file.setLastModified(now() + 30_000)
            }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
