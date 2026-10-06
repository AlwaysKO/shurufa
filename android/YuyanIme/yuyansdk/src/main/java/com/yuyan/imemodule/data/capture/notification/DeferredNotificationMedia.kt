package com.yuyan.imemodule.data.capture.notification

import java.io.File
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject
import com.yuyan.imemodule.data.capture.sha256

internal data class DeferredMediaItem(val id: String, val snapshot: NotificationSnapshot, val file: File)
internal sealed interface DeferredMediaStage {
    data object Stored : DeferredMediaStage
    data object AlreadyStored : DeferredMediaStage
    data class Rejected(val reason: String) : DeferredMediaStage
}

internal class DeferredNotificationMedia(
    private val directory: File,
    private val maximumItems: Int = 32,
    private val maximumItemBytes: Long = 16L * 1024 * 1024,
    private val maximumTotalBytes: Long = 64L * 1024 * 1024,
    private val yieldAfterChunk: () -> Unit = {},
) {
    /** 原始编码图片仅供本地归一化；完成目录原子提交前不会出现在待处理列表。 */
    @Synchronized fun stage(snapshot: NotificationSnapshot, open: () -> InputStream?): DeferredMediaStage {
        val metadata = encode(snapshot).toByteArray(Charsets.UTF_8)
        if (metadata.size > 64 * 1024) return DeferredMediaStage.Rejected("metadata_limit")
        val id = sha256(metadata)
        val target = File(directory, id)
        if (target.isDirectory && File(target, "snapshot.json").isFile && File(target, "source").isFile)
            return DeferredMediaStage.AlreadyStored
        directory.mkdirs()
        // 只有未提交的临时目录会清理，已经提交的待办从不按容量淘汰。
        directory.listFiles()?.filter { it.name.startsWith(".pending-") }?.forEach { it.deleteRecursively() }
        val tasks = committedDirectories()
        if (tasks.size >= maximumItems) return DeferredMediaStage.Rejected("item_count_limit")
        val remaining = maximumTotalBytes - tasks.sumOf { File(it, "source").length() }
        if (remaining <= 0) return DeferredMediaStage.Rejected("total_bytes_limit")
        val temporary = File(directory, ".pending-${UUID.randomUUID()}")
        try {
            check(temporary.mkdirs())
            val input = open() ?: return DeferredMediaStage.Rejected("source_unavailable")
            input.use { stream ->
                java.io.FileOutputStream(File(temporary, "source")).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var written = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > maximumItemBytes || written > remaining)
                            return DeferredMediaStage.Rejected("bytes_limit")
                        output.write(buffer, 0, count)
                        yieldAfterChunk()
                    }
                    if (written == 0L) return DeferredMediaStage.Rejected("empty_source")
                    output.fd.sync()
                }
            }
            java.io.FileOutputStream(File(temporary, "snapshot.json")).use {
                it.write(metadata)
                it.fd.sync()
            }
            check(temporary.renameTo(target))
            return DeferredMediaStage.Stored
        } catch (_: Exception) {
            return DeferredMediaStage.Rejected("copy_failed")
        } finally { temporary.deleteRecursively() }
    }

    /** 仅检查提交目录，不在暂停期间读取通知正文或图片。 */
    @Synchronized fun hasPending(): Boolean = committedDirectories().isNotEmpty()

    @Synchronized fun pending(): List<DeferredMediaItem> = committedDirectories().mapNotNull { task ->
        runCatching {
            val metadata = File(task, "snapshot.json")
            check(metadata.length() in 1..64 * 1024L)
            DeferredMediaItem(task.name, decode(metadata.readText()), File(task, "source").also { check(it.isFile) })
        }.getOrNull()
    }.sortedBy { it.snapshot.postedAtMillis }

    /** 失败或暂停保留原始文件；确认归一化消息已持久化后才移除本地暂存。 */
    suspend fun processNext(allowed: () -> Boolean, consume: suspend (NotificationSnapshot, File) -> Boolean): Boolean {
        if (!allowed()) return false
        for (item in pending()) {
            if (!allowed()) return false
            if (!consume(item.snapshot, item.file)) continue
            if (!allowed()) return false
            synchronized(this) { File(directory, item.id).deleteRecursively() }
            return true
        }
        return false
    }

    private fun committedDirectories() = directory.listFiles()?.filter {
        it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}"))
    }.orEmpty()

    private fun encode(value: NotificationSnapshot): String = JSONObject().apply {
        put("package", value.packageName); put("key", value.notificationKey)
        put("title", value.title ?: JSONObject.NULL); put("text", value.text ?: JSONObject.NULL)
        put("posted", value.postedAtMillis); put("group", value.isGroupConversation)
        put("sender", value.senderName ?: JSONObject.NULL); put("uri", value.mediaUri ?: JSONObject.NULL)
        put("readable", value.mediaUriReadable); put("summary", value.summaryText ?: JSONObject.NULL)
        put("messaging", value.isMessagingStyle); put("messageTime", value.sourceMessageTimestampMillis ?: JSONObject.NULL)
        put("conversation", value.stableConversationId ?: JSONObject.NULL); put("profile", value.profileKey)
    }.toString()

    private fun decode(value: String): NotificationSnapshot {
        val data = JSONObject(value)
        fun optional(key: String): String? = if (data.isNull(key)) null else data.getString(key)
        return NotificationSnapshot(data.getString("package"), data.getString("key"), optional("title"), optional("text"),
            data.getLong("posted"), data.getBoolean("group"), optional("sender"), optional("uri"), data.getBoolean("readable"),
            optional("summary"), data.getBoolean("messaging"), if (data.isNull("messageTime")) null else data.getLong("messageTime"),
            optional("conversation"), data.getString("profile"))
    }
}
