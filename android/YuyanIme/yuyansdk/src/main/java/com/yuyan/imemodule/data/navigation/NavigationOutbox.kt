package com.yuyan.imemodule.data.navigation

import java.io.File
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

internal class NavigationOutbox(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    fun contains(id: String): Boolean = File(directory, "$id.json").isFile || File(directory, "$id.seen").isFile

    fun enqueue(record: NavigationRecord, allowed: () -> Boolean = { true }): Boolean = runCatching {
        if (!allowed()) return false
        require(record.id.matches(Regex("[a-f0-9-]{36}")))
        require(record.image.isNotEmpty() && record.image.size <= 3 * 1024 * 1024)
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "${record.id}.json")
        if (contains(record.id)) return true
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
            check(file.isFile && file.length() == payload.toByteArray(Charsets.UTF_8).size.toLong())
        }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        true
    }.getOrDefault(false)

    private fun files() = directory.listFiles { file -> file.name.matches(Regex("[a-f0-9-]{36}\\.json")) }.orEmpty().sortedBy { it.lastModified() }
    fun count(): Int = files().size

    suspend fun drain(allowed: () -> Boolean, send: suspend (String) -> String?) {
        if (!allowed()) return
        // 只清理过期的小回执；离线多日的图片仍保留补传。
        directory.listFiles { file -> file.isFile && file.name.matches(Regex("[a-f0-9-]{36}\\.seen")) }
            .orEmpty().filter { now() - it.lastModified() > 7 * 86_400_000L }.forEach { it.delete() }
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
                    receipt.optString("sha256") == record.getString("sha256")) {
                    // 先落盘去重回执再删除图片，重启/再次打开也不会重新截图。
                    val marker = AtomicFile(File(directory, "${file.nameWithoutExtension}.seen"))
                    val stream = marker.startWrite()
                    try {
                        stream.write(1); marker.finishWrite(stream)
                        check(marker.baseFile.isFile && marker.readFully().contentEquals(byteArrayOf(1)))
                    }
                    catch (error: Exception) { marker.failWrite(stream); throw error }
                    AtomicFile(file).delete()
                }
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
