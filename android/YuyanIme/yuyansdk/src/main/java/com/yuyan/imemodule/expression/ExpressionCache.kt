package com.yuyan.imemodule.expression

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        target.parentFile?.mkdirs()
        val part = Files.createTempFile(
            target.parentFile.toPath(),
            "${target.name}.",
            ".part",
        ).toFile()
        return try {
            input.use { source -> part.outputStream().use(source::copyTo) }
            if (sha256(part) != expectedSha256) {
                part.delete()
                validExisting
            } else {
                runCatching {
                    Files.move(
                        part.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    Files.move(
                        part.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                target
            }
        } finally {
            part.delete()
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
