package com.yuyan.imemodule.data.completion

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** 版本与数据作为一个快照替换，避免只存游标导致重启后永远缺词。 */
internal class CompletionCache(directory: File, deviceId: String, baseUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = MessageDigest.getInstance("SHA-256").digest("$deviceId\n$baseUrl".toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val file = File(directory, "completions-$key.json")

    fun load(): CompletionSyncResponse = try {
        json.decodeFromString(CompletionSyncResponse.serializer(), file.readText())
    } catch (_: Exception) { CompletionSyncResponse() }

    fun save(version: Int, candidates: List<CompletionCandidate>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val data = json.encodeToString(CompletionSyncResponse.serializer(), CompletionSyncResponse(version, false, candidates))
        FileOutputStream(temporary).use { output ->
            output.write(data.toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
        check(temporary.renameTo(file)) { "无法原子替换补全缓存" }
    }
}
