package com.yuyan.imemodule.data.completion

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import org.json.JSONObject

/** APK内无损压缩；仅在后台初始化时展开/校验，按键仍查询只读映射。 */
internal object IndexAssetCache {
    fun load(context: Context, name: String, manifest: String): ByteBuffer {
        val metadata = context.assets.open(manifest).bufferedReader().use { JSONObject(it.readText()) }
        return map(File(context.noBackupFilesDir, "completion_indices"), name,
            metadata.getLong("size"), metadata.getString("sha256")) {
            context.assets.open("completion/$name.t9idx")
        }
    }

    @Synchronized
    fun map(root: File, name: String, size: Long, sha256: String, open: () -> InputStream): ByteBuffer {
        require(name.matches(Regex("[a-z_]+")))
        require(size in 16..128L * 1024 * 1024 && sha256.matches(Regex("[0-9a-f]{64}")))
        if (!root.mkdirs() && !root.isDirectory) throw IOException("无法准备词库目录")
        // JVM内互斥避免重叠锁；文件锁防止应用多进程同时展开/清理同一个索引。
        return RandomAccessFile(File(root, ".lock"), "rw").use { lock ->
            lock.channel.lock().use {
                val target = File(root, "$name-$sha256.t9idx")
                val temporary = File(root, "$name.tmp")
                try {
                    val valid = target.isFile && target.length() == size &&
                        FileInputStream(target).use { digest(it) } == sha256
                    if (!valid) {
                        val checksum = MessageDigest.getInstance("SHA-256")
                        open().use { input ->
                            FileOutputStream(temporary).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > size) throw IOException("词库长度超出清单")
                                    output.write(buffer, 0, count)
                                    checksum.update(buffer, 0, count)
                                }
                                if (total != size || hex(checksum.digest()) != sha256)
                                    throw IOException("词库完整性校验失败")
                                output.fd.sync()
                            }
                        }
                        if (!temporary.renameTo(target)) throw IOException("无法保存词库索引")
                    }
                    val mapped = FileInputStream(target).use {
                        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                    }
                    // 新版完整发布后只清理本缓存的旧版本；已经映射的旧文件仍可正常读取。
                    val owned = Regex("$name-[0-9a-f]{64}\\.t9idx")
                    root.listFiles()?.filter { it != target && it.name.matches(owned) }?.forEach { it.delete() }
                    mapped
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        return hex(hash.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
