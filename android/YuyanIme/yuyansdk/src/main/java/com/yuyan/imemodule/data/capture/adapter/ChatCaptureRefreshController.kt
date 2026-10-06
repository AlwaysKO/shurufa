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
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    private val localMutex = kotlinx.coroutines.sync.Mutex()
    private val cache = ChatCapturePolicyCache()
    @Volatile private var versions: Map<String, CaptureAppVersion> = emptyMap()
    private var sourceEpoch = 0L
    private var refreshedSource: String? = null
    private var refreshedAt: Long? = null
    private var restoredSource: String? = null
    private var restoredAt: Long? = null
    suspend fun restore(source: String) {
        localMutex.lock()
        try {
            if (!allowed()) return
            val epoch = switchSource(source)
            val time = now()
            if (restoredSource == source && restoredAt?.let { time - it in 0 until 60_000L } == true) return
            loadLocal(source, epoch, time)
        } finally { localMutex.unlock() }
    }
    suspend fun refresh(source: String, minimumIntervalMs: Long = 30 * 60_000L, userInitiated: Boolean = false) {
        refreshMutex.lock()
        try {
            if (!allowed()) return
            val epoch = switchSource(source)
            val time = now()
            if (!userInitiated && refreshedSource == source && refreshedAt?.let { time - it in 0 until minimumIntervalMs } == true) return
            refreshedSource = source
            refreshedAt = time
            localMutex.lock()
            try { loadLocal(source, epoch, time) } finally { localMutex.unlock() }
            if (!allowed() || synchronized(cache) { sourceEpoch != epoch }) return
            val response = try { fetch(source) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (response != null && allowed() && cache.accept(source, response, epoch)) {
                runCatching { writeCache(source, response) }
            }
        } finally { refreshMutex.unlock() }
    }
    private fun loadLocal(source: String, epoch: Long, time: Long) {
        val nextVersions = mutableMapOf<String, CaptureAppVersion>()
        for (pkg in listOf("com.tencent.mm", "com.ss.android.ugc.aweme")) {
            if (!allowed()) return
            runCatching { loadVersion(pkg) }.getOrNull()?.let { nextVersions[pkg] = it }
        }
        if (!allowed()) return
        val raw = runCatching { readCache(source) }.getOrNull()
        // 本地读取不持有内存快照锁；撤权/来源切换时整份丢弃，不能发布半份宿主版本。
        synchronized(cache) {
            if (!allowed() || sourceEpoch != epoch) return
            raw?.let { cache.accept(source, it, epoch) }
            versions = nextVersions.toMap()
            restoredSource = source
            restoredAt = time
        }
    }
    private fun switchSource(source: String): Long = synchronized(cache) {
        cache.switchSource(source).also { sourceEpoch = it }
    }
    fun version(packageName: String): CaptureAppVersion? = versions[packageName]
    fun policy(source: String): ChatCapturePolicy = synchronized(cache) {
        switchSource(source)
        cache.current()
    }
    fun rule(source: String, packageName: String): ChatCaptureRule? = synchronized(cache) {
        version(packageName)?.let { policy(source).rule(packageName, it.code) }
            ?: ChatCapturePolicy.builtIn().rule(packageName, 0)
    }
}
