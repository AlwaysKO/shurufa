package com.yuyan.imemodule.data.capture.adapter

data class CaptureAppVersion(val code: Long, val name: String)
class ChatCaptureRefreshController(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val allowed: () -> Boolean,
    private val loadVersion: (String) -> CaptureAppVersion?,
    private val readCache: (String) -> String?,
    private val writeCache: (String, String) -> Unit,
    private val fetch: suspend (String) -> String?,
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val cache = ChatCapturePolicyCache()
    private val versions = java.util.concurrent.ConcurrentHashMap<String, CaptureAppVersion>()
    private var refreshedSource: String? = null
    private var refreshedAt: Long? = null
    suspend fun refresh(source: String) {
        mutex.lock()
        try {
            if (!allowed()) return
            val epoch = cache.switchSource(source)
            val time = now()
            if (refreshedSource == source && refreshedAt?.let { time - it in 0 until 300_000 } == true) return
            refreshedSource = source
            refreshedAt = time
            for (pkg in listOf("com.tencent.mm", "com.ss.android.ugc.aweme")) {
                val value = runCatching { loadVersion(pkg) }.getOrNull()
                if (value == null) versions.remove(pkg) else versions[pkg] = value
            }
            if (!allowed()) return
            runCatching { readCache(source) }.getOrNull()?.let { cache.accept(source, it, epoch) }
            val response = try { fetch(source) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (response != null && allowed() && cache.accept(source, response, epoch)) {
                runCatching { writeCache(source, response) }
            }
        } finally { mutex.unlock() }
    }
    fun version(packageName: String): CaptureAppVersion? = versions[packageName]
    fun policy(source: String): ChatCapturePolicy = cache.currentForSource(source)
    fun rule(source: String, packageName: String): ChatCaptureRule? =
        version(packageName)?.let { policy(source).rule(packageName, it.code) }
            ?: ChatCapturePolicy.builtIn().rule(packageName, 0)
}
