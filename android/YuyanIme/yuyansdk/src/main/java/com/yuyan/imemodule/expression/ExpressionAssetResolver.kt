package com.yuyan.imemodule.expression

import java.io.File
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 预览和发送使用同一条经过 SHA 校验的素材路径。 */
class ExpressionAssetResolver(
    private val cache: ExpressionCache,
    private val openBuiltIn: (String) -> InputStream,
    private val download: suspend (String, String, String, String) -> File?,
) {
    suspend fun resolve(version: String, path: String, sha256: String, url: String?): File? =
        withContext(Dispatchers.IO) {
            try {
                cache.validFile(version, path, sha256)?.let { return@withContext it }
                val builtIn = try {
                    openBuiltIn(path).use { cache.writeVerified(version, path, sha256, it) }
                } catch (_: IOException) {
                    null
                }
                if (builtIn != null) return@withContext builtIn
                download(version, path, url?.takeIf { it.isNotBlank() } ?: "/uploads/expression/$path", sha256)
            } catch (cancelled: CancellationException) {
                // 取消不是加载失败；由调用方清理选择状态，不能触发失败交付或后续下载。
                throw cancelled
            } catch (_: Exception) {
                // 缓存校验可能与清缓存并发；读取/路径/下载异常统一交付可重试失败。
                null
            }
        }
}
