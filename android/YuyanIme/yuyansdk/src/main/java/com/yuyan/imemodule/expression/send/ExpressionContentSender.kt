package com.yuyan.imemodule.expression.send

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.util.Log
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.yuyan.imemodule.R
import com.yuyan.imemodule.BuildConfig
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.yuyan.imemodule.expression.ExpressionCache

class ExpressionContentSender(
    private val context: Context,
    private val inputConnection: () -> InputConnection?,
    private val editorMimeTypes: () -> Array<String>?,
    private val editorInfo: () -> EditorInfo? = { null },
) : ExpressionSender {
    override suspend fun send(expression: PreparedExpression): ExpressionSendResult {
        val diagnosticId = if (BuildConfig.DEBUG && expression.mimeType == "image/gif")
            diagnosticSequence.incrementAndGet() else null
        val delivery = try {
            prepareDeliveryFile(expression)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnosticId?.let { diagnosticLog(it, "stage=prepareDelivery error=${error.javaClass.simpleName}") }
            return ExpressionSendResult.Failed(context.getString(R.string.expression_image_send_failed))
        }
        return sendPrepared(delivery, diagnosticId)
    }

    private suspend fun sendPrepared(expression: PreparedExpression, diagnosticId: Long?): ExpressionSendResult {
        diagnosticId?.let { diagnoseFile(it, expression) }
        val validGif = if (expression.mimeType == "image/gif") withContext(Dispatchers.IO) {
            runCatching {
                expression.file.inputStream().use { input ->
                    val header = ByteArray(6)
                    input.read(header) == 6 && (header.contentEquals("GIF89a".toByteArray()) ||
                        header.contentEquals("GIF87a".toByteArray()))
                }
            }.getOrDefault(false)
        } else false
        val policy = ExpressionDeliverySettings.current(context)
        // 诊断与配置 IO 完成后才获取当前编辑器和连接，不跨挂起点持有旧目标。
        return withContext(Dispatchers.Main.immediate) {
            val currentEditorInfo = editorInfo()
            val connection = inputConnection() ?: return@withContext ExpressionSendResult.UnsupportedTarget
            val target = currentEditorInfo?.packageName
            if (target in ExpressionDeliveryPolicy.packages) {
                @Suppress("DEPRECATION")
                val info = runCatching { context.packageManager.getPackageInfo(target!!, 0) }.getOrNull()
                @Suppress("DEPRECATION")
                val versionCode = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
                val rule = policy.match(target!!, expression.mimeType, info?.versionName, versionCode, Build.VERSION.SDK_INT)
                if (rule == null || !rule.enabled || !supportsDeliveryRule(rule, currentEditorInfo!!)) {
                    diagnosticId?.let { diagnosticLog(it, "stage=target reason=deliveryPolicyUnsupported revision=${policy.revision}") }
                    return@withContext ExpressionSendResult.UnsupportedTarget
                }
                if (rule.method == "private_command") {
                    if (expression.mimeType == "image/gif" && !validGif) return@withContext ExpressionSendResult.Failed(context.getString(R.string.expression_invalid_gif))
                    return@withContext sendPrivateCommand(expression, connection, rule, diagnosticId)
                }
            }
            // 其他目标保持真实MIME协商；URI绝不作为正文提交。
            if (!supportsExpressionMimeType(
                    expressionMimeType = expression.mimeType,
                    editorInfo = currentEditorInfo,
                    fallbackMimeTypes = editorMimeTypes,
                )
            ) return@withContext ExpressionSendResult.UnsupportedTarget
            runCatching {
                val uri = contentUri(expression.file)
                val description = ClipDescription(expression.displayName, arrayOf(expression.mimeType))
                diagnosticId?.let {
                    diagnosticLog(it, "target=${currentEditorInfo?.packageName} clipMime=${expression.mimeType}")
                }
                val committed = if (currentEditorInfo != null) {
                    InputConnectionCompat.commitContent(
                        connection,
                        currentEditorInfo,
                        InputContentInfoCompat(uri, description, null),
                        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                        null,
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    val content = InputContentInfo(
                        uri,
                        description,
                        null,
                    )
                    connection.commitContent(content, INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                } else {
                    false
                }
                diagnosticId?.let { diagnosticLog(it, "committed=$committed") }
                if (committed) {
                    ExpressionSendResult.Sent
                } else {
                    ExpressionSendResult.Failed(context.getString(R.string.expression_input_rejected_image))
                }
            }.getOrElse { error ->
                ExpressionSendResult.Failed(
                    error.message ?: context.getString(R.string.expression_image_send_failed),
                )
            }
        }
    }

    /** 只在实际发送时归一化文件，不把整批预览原件复制出有容量限制的查询缓存。 */
    private suspend fun prepareDeliveryFile(expression: PreparedExpression): PreparedExpression = withContext(Dispatchers.IO) {
        val extension = when (expression.mimeType) {
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> error("unsupported image MIME")
        }
        val file = expression.file
        val properExtension = file.extension.equals(extension, ignoreCase = true) ||
            (extension == "jpg" && file.extension.equals("jpeg", ignoreCase = true))
        if (properExtension && runCatching { contentUri(file) }.isSuccess) {
            // 已有授权路径保持原发送行为；只有我们管理的交付副本需要刷新回收保护。
            val deliveryRoot = File(context.cacheDir, "expression/ime-delivery").canonicalFile
            if (file.canonicalFile.parentFile == deliveryRoot) deliveryCacheMutex.withLock {
                currentCoroutineContext().ensureActive()
                check(file.isFile && file.setLastModified(System.currentTimeMillis()))
            }
            return@withContext expression
        }
        check(file.isFile)
        // 查询原件是无后缀的SHA文件，不能直接暴露其目录或假装Provider已支持。
        check(file.length() <= DELIVERY_CACHE_MAX_BYTES)
        val source = file.inputStream().use(::fingerprint)
        check(source.size <= DELIVERY_CACHE_MAX_BYTES)
        deliveryCacheMutex.withLock {
            currentCoroutineContext().ensureActive()
            val cache = ExpressionCache(context.cacheDir)
            val name = "${source.sha256}.$extension"
            val target = cache.file("ime-delivery", name)
            val root = requireNotNull(target.parentFile)
            check(root.mkdirs() || root.isDirectory)
            val now = System.currentTimeMillis()
            if (target.isFile && target.inputStream().use(::fingerprint).sha256 == source.sha256) {
                check(target.setLastModified(now))
                return@withLock expression.copy(file = target, displayName = target.name)
            }
            // URI交接是异步的：不能发送后立即删，也不能为新发送淘汰一小时内的交付文件。
            val entries = root.listFiles().orEmpty().filter { it.isFile && it != target }
                .sortedBy { it.lastModified() }
            entries.filter { now - it.lastModified() >= DELIVERY_CACHE_TTL_MS }.forEach { it.delete() }
            var bytes = root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
            for (entry in entries) {
                if (bytes + source.size <= DELIVERY_CACHE_MAX_BYTES) break
                if (entry.isFile && now - entry.lastModified() >= DELIVERY_READ_GRACE_MS) {
                    val size = entry.length()
                    if (entry.delete()) bytes -= size
                }
            }
            check(bytes + source.size <= DELIVERY_CACHE_MAX_BYTES) { "delivery cache full" }
            val saved = file.inputStream().use { cache.writeVerified("ime-delivery", name, source.sha256, it) }
            // 写入失败时writeVerified可能返回查询缓存原件，不能把它误当作可授权文件。
            check(saved?.canonicalFile == target.canonicalFile)
            currentCoroutineContext().ensureActive()
            check(target.setLastModified(now))
            expression.copy(file = target, displayName = target.name)
        }
    }

    private fun supportsDeliveryRule(rule: ExpressionDeliveryRule, editor: EditorInfo): Boolean = runCatching {
        for ((key, expected) in rule.requiredEditorExtras) {
            @Suppress("DEPRECATION")
            val actual = editor.extras?.get(key)
            if (actual !is Int || actual != expected) return@runCatching false
        }
        if (!rule.requireCompatIme) return@runCatching true
        val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val component = selected?.let(ComponentName::unflattenFromString) ?: return@runCatching false
        component.packageName == context.packageName && component.className == WECHAT_COMPAT_IME
    }.getOrDefault(false)

    private fun sendPrivateCommand(
        expression: PreparedExpression, connection: InputConnection, rule: ExpressionDeliveryRule, diagnosticId: Long?,
    ): ExpressionSendResult {
        var grantedUri: android.net.Uri? = null
        var confirmation: Long? = null
        return try {
            val uri = contentUri(expression.file)
            context.grantUriPermission(rule.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            grantedUri = uri
            // 在弹框抢走焦点前绑定原编辑节点；交接本身不会清空输入。
            if (rule.packageName == WECHAT_PACKAGE) confirmation = WechatExpressionConfirmation.arm(connection)
            // 接收端已声明并在当前版本核对的互操作命令，不写入聊天正文。
            val submitted = connection.performPrivateCommand(
                requireNotNull(rule.action),
                Bundle().apply { putParcelable(requireNotNull(rule.uriKey), uri) },
            )
            diagnosticId?.let { diagnosticLog(it, "target=${rule.packageName} route=private_command rule=${rule.id} submitted=$submitted") }
            if (submitted) {
                // Android异步协议只能证明命令已交接，不能证明弹框/用户确认/动画发送完成。
                if (rule.packageName == WECHAT_PACKAGE) ExpressionSendResult.WechatSubmitted else ExpressionSendResult.AppSubmitted
            } else {
                confirmation?.let(WechatExpressionConfirmation::cancel)
                context.revokeUriPermission(rule.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ExpressionSendResult.Failed(context.getString(R.string.expression_image_send_failed))
            }
        } catch (error: Exception) {
            confirmation?.let(WechatExpressionConfirmation::cancel)
            grantedUri?.let { uri -> runCatching {
                context.revokeUriPermission(rule.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } }
            ExpressionSendResult.Failed(context.getString(R.string.expression_image_send_failed))
        }
    }

    /** 仅 debug GIF 的交付证据；文件名与 URI 哈希化，绝不记录查询文字或异常 message。 */
    private suspend fun diagnoseFile(id: Long, expression: PreparedExpression) = withContext(Dispatchers.IO) {
        try {
            val uri = contentUri(expression.file)
            val source = expression.file.inputStream().use(::fingerprint)
            val delivered = requireNotNull(context.contentResolver.openInputStream(uri)).use(::fingerprint)
            val metadata = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use "metadata=empty"
                val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).orEmpty()
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                // 只保留已知扩展名，不让任意显示名或文件名进入日志。
                val extension = name.substringAfterLast('.', "").lowercase().takeIf { it == "gif" || it == "0" } ?: "other"
                "displayNameSha256=${sha256(name.toByteArray())} displayExtension=$extension providerSize=$size"
            } ?: "metadata=unavailable"
            diagnosticLog(
                id,
                "fileSha256=${source.sha256} fileSize=${source.size} fileMagic=${source.magic} " +
                    "uriSha256=${delivered.sha256} uriSize=${delivered.size} uriMagic=${delivered.magic} " +
                    "uriIdentitySha256=${sha256(uri.toString().toByteArray())} " +
                    "resolverMime=${context.contentResolver.getType(uri)} $metadata " +
                    "ioMainThread=${Looper.myLooper() == Looper.getMainLooper()}",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnosticLog(id, "diagnosticError=${error.javaClass.simpleName}")
        }
    }

    private data class Fingerprint(val sha256: String, val size: Long, val magic: String)

    private fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val magic = ArrayList<Byte>(6)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            for (index in 0 until minOf(count, 6 - magic.size)) magic.add(buffer[index])
            size += count
        }
        return Fingerprint(digest.digest().hex(), size, magic.toByteArray().hex())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun diagnosticLog(id: Long, message: String) {
        // 即使日志设施异常也不能影响实际交付。
        runCatching { Log.d("ExpressionSendDiag", "id=$id $message") }
    }

    suspend fun saveToGallery(expression: PreparedExpression): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, expression.displayName)
                put(MediaStore.Images.Media.MIME_TYPE, expression.mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/YuyanExpressions",
                )
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { output ->
                expression.file.inputStream().use { input -> input.copyTo(output) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    fun copyToClipboard(expression: PreparedExpression): Boolean = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newUri(context.contentResolver, expression.displayName, contentUri(expression.file)),
        )
        true
    }.getOrDefault(false)

    private fun contentUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.expression.fileprovider",
        file,
    )

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val WECHAT_COMPAT_IME = "com.yuyan.imemodule.compat.com.sohu.inputmethod.sogou.DebugGifImeService"
        private const val INPUT_CONTENT_GRANT_READ_URI_PERMISSION = 1
        private val diagnosticSequence = AtomicLong()
        private val deliveryCacheMutex = Mutex()
        private const val DELIVERY_CACHE_MAX_BYTES = 64L * 1024 * 1024
        private const val DELIVERY_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val DELIVERY_READ_GRACE_MS = 60L * 60 * 1000

        fun mimeOf(format: String): String = when (format.lowercase()) {
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
    }
}

internal fun supportsExpressionMimeType(
    expressionMimeType: String,
    editorInfo: EditorInfo?,
    sdkInt: Int = Build.VERSION.SDK_INT,
    fallbackMimeTypes: () -> Array<out String>?,
): Boolean = buildList {
    if (editorInfo != null) {
        addAll(EditorInfoCompat.getContentMimeTypes(editorInfo).asList())
    } else if (sdkInt >= Build.VERSION_CODES.N_MR1) {
        addAll(fallbackMimeTypes().orEmpty().asList())
    }
}.distinct().any { accepted ->
    ClipDescription.compareMimeTypes(expressionMimeType, accepted)
}
