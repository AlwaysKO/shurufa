package com.yuyan.imemodule.expression

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

class ExpressionCache(cacheDir: File) {
    private val root = File(cacheDir, "expression")

    fun file(version: String, relativePath: String): File = safeFile(version, relativePath)

    fun validFile(version: String, relativePath: String, expectedSha256: String): File? =
        safeFile(version, relativePath).takeIf { it.isFile && sha256(it) == expectedSha256 }

    fun writeVerified(
        version: String,
        relativePath: String,
        expectedSha256: String,
        input: InputStream,
    ): File? {
        val target = safeFile(version, relativePath)
        val validExisting = validFile(version, relativePath, expectedSha256)
        val parent = requireNotNull(target.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "cannot create expression cache directory" }
        val part = File.createTempFile(target.name.padEnd(3, '_') + ".", ".part", parent)
        return try {
            input.use { source ->
                FileOutputStream(part).use { output ->
                    source.copyTo(output)
                    output.fd.sync()
                }
            }
            if (sha256(part) != expectedSha256) {
                validExisting
            } else {
                replaceSafely(part, target, expectedSha256) ?: validExisting
            }
        } finally {
            part.delete()
        }
    }

    /**
     * 同目录 renameTo 在 Android/Linux 上是首选原子替换；少数文件系统拒绝覆盖时，先备份旧文件，
     * 再 rename，最后才使用 fsync 后的复制回退。任一步失败都会恢复旧文件并清理中间文件。
     */
    private fun replaceSafely(part: File, target: File, expectedSha256: String): File? {
        if (part.renameTo(target)) return target

        val parent = requireNotNull(target.parentFile)
        val backup = File.createTempFile(target.name.padEnd(3, '_') + ".", ".bak", parent).apply {
            delete()
        }
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) {
            backup.delete()
            return null
        }

        var installed = false
        return try {
            installed = part.renameTo(target)
            if (!installed) {
                copyAndSync(part, target)
                installed = sha256(target) == expectedSha256
            }
            if (installed) target else null
        } catch (_: Exception) {
            null
        } finally {
            if (installed) {
                backup.delete()
            } else {
                target.delete()
                val restored = !hadTarget || backup.renameTo(target) || runCatching {
                    copyAndSync(backup, target)
                    true
                }.onFailure { target.delete() }.getOrDefault(false)
                if (restored) backup.delete()
            }
        }
    }

    private fun copyAndSync(sourceFile: File, targetFile: File) {
        FileOutputStream(targetFile).use { output ->
            sourceFile.inputStream().use { source -> source.copyTo(output) }
            output.fd.sync()
        }
    }

    private fun safeFile(version: String, relativePath: String): File {
        require(version.matches(Regex("[A-Za-z0-9._-]+"))) { "invalid cache version" }
        val versionRoot = File(root, version).canonicalFile
        val target = File(versionRoot, relativePath).canonicalFile
        require(target.path.startsWith(versionRoot.path + File.separator)) {
            "invalid expression cache path"
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
