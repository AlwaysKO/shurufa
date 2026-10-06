package com.yuyan.imemodule.data.capture.adapter

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ChatCaptureRefreshTest {
    @Test fun versionRefreshIsBoundedButUpdatesWithoutImeRestart() = runBlocking {
        var now = 0L; var loads = 0; var version = 1L; var requests = 0
        val runtime = ChatCaptureRefreshController(now = { now }, allowed = { true },
            loadVersion = { loads++; CaptureAppVersion(version, "v$version") },
            readCache = { null }, writeCache = { _, _ -> }, fetch = { requests++; null })
        runtime.refresh("a")
        assertEquals(2, loads)
        assertEquals(1L, runtime.version("com.tencent.mm")!!.code)
        version = 2
        repeat(20) { runtime.refresh("a") }
        assertEquals(2, loads); assertEquals(1, requests)
        now = 1_800_000
        runtime.refresh("a")
        assertEquals(4, loads)
        assertEquals(2L, runtime.version("com.tencent.mm")!!.code)
    }
    @Test fun localRestoreDoesNotFetchAndManualRefreshBypassesAutomaticInterval() = runBlocking {
        var now = 0L; var requests = 0
        val runtime = ChatCaptureRefreshController(now = { now }, allowed = { true },
            loadVersion = { CaptureAppVersion(7, "v7") },
            readCache = { """{"schemaVersion":1,"revision":4,"rules":[]}""" }, writeCache = { _, _ -> },
            fetch = { requests++; null })
        runtime.restore("a")
        assertEquals(0, requests); assertEquals(4L, runtime.policy("a").revision)
        assertEquals(7L, runtime.version("com.tencent.mm")!!.code)
        runtime.refresh("a", minimumIntervalMs = 7_200_000)
        now = 1_800_000
        runtime.refresh("a", minimumIntervalMs = 7_200_000)
        assertEquals(1, requests)
        runtime.refresh("a", minimumIntervalMs = 7_200_000, userInitiated = true)
        assertEquals(2, requests)
    }
    @Test fun inputGameGuardSkipsIoAndInvalidCacheNeverReplacesBuiltin() = runBlocking {
        var allowed = false; var loads = 0; var writes = 0
        val runtime = ChatCaptureRefreshController(allowed = { allowed },
            loadVersion = { loads++; CaptureAppVersion(1, "1") },
            readCache = { "<html>bad</html>" }, writeCache = { _, _ -> writes++ }, fetch = { "bad" })
        runtime.refresh("a"); assertEquals(0, loads)
        allowed = true; runtime.refresh("a")
        assertEquals(0L, runtime.policy("a").revision); assertEquals(0, writes)
    }
    @Test fun sourceChangeAndReturnRejectsInflightOldAuthorityResponse() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val response = kotlinx.coroutines.CompletableDeferred<String>()
        val runtime = ChatCaptureRefreshController(allowed = { true }, loadVersion = { CaptureAppVersion(1, "1") },
            readCache = { null }, writeCache = { _, _ -> }, fetch = { started.complete(Unit); response.await() })
        val job = launch { runtime.refresh("a") }
        started.await()
        runtime.policy("b")
        runtime.policy("a")
        response.complete("""{"schemaVersion":1,"revision":9,"rules":[]}""")
        job.join()
        assertEquals(0L, runtime.policy("a").revision)
    }
    @Test fun simultaneousSourcesNeverReadAnotherAuthorityPolicy() = runBlocking {
        val runtime = ChatCaptureRefreshController(allowed={true}, loadVersion={CaptureAppVersion(1,"1")},
            readCache={null}, writeCache={_,_->}, fetch={source -> """{"schemaVersion":1,"revision":${if(source=="a") 11 else 22},"rules":[]}"""})
        runtime.refresh("a"); runtime.refresh("b")
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = listOf("a" to 11L,"b" to 22L).map { (source,revision) -> Thread {
            start.await(); repeat(100_000) { if(runtime.policy(source).revision != revision) errors.incrementAndGet() }
        }.apply { start() } }
        start.countDown(); threads.forEach { it.join() }
        assertEquals("两个来源切换与读快照必须原子",0,errors.get())
    }

    @Test fun foregroundLocalRestoreDetectsHostUpgradeWithoutNetworkOrImeRestart() = runBlocking {
        var now = 0L; var loads = 0; var version = 1L; var requests = 0; var reads = 0
        val runtime = ChatCaptureRefreshController(now = { now }, allowed = { true },
            loadVersion = { loads++; CaptureAppVersion(version, "v$version") },
            readCache = { reads++; versionedPolicy }, writeCache = { _, _ -> }, fetch = { requests++; null })
        runtime.restore("a")
        assertEquals("wechat-v1", runtime.rule("a", "com.tencent.mm")!!.id)
        version = 2
        now = 59_999
        repeat(100) { runtime.restore("a") }
        assertEquals(2, loads); assertEquals(1, reads)
        now = 60_000
        runtime.restore("a")
        assertEquals(4, loads); assertEquals(2, reads)
        assertEquals(2L, runtime.version("com.tencent.mm")!!.code)
        assertEquals("wechat-v2", runtime.rule("a", "com.tencent.mm")!!.id)
        assertEquals(0, requests)
    }

    @Test fun localRestoreDoesNotWaitForInflightNetworkAndRejectsOldSourceResponse() = runBlocking {
        var now = 0L; var version = 1L; var writes = 0
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val response = kotlinx.coroutines.CompletableDeferred<String>()
        val runtime = ChatCaptureRefreshController(now = { now }, allowed = { true },
            loadVersion = { CaptureAppVersion(version, "v$version") },
            readCache = { """{"schemaVersion":1,"revision":4,"rules":[]}""" },
            writeCache = { _, _ -> writes++ }, fetch = { started.complete(Unit); response.await() })
        val job = launch { runtime.refresh("a") }
        try {
            started.await()
            now = 60_000; version = 2
            val completed = kotlinx.coroutines.withTimeoutOrNull(500) { runtime.restore("b"); true }
            assertEquals("本地版本恢复不能被配置网络请求阻塞", true, completed)
            assertEquals(2L, runtime.version("com.tencent.mm")!!.code)
            assertEquals(4L, runtime.policy("b").revision)
            response.complete("""{"schemaVersion":1,"revision":9,"rules":[]}""")
            job.join()
            assertEquals(0, writes)
            assertEquals(4L, runtime.policy("a").revision)
        } finally { job.cancel(); job.join() }
    }

    @Test fun consentRevocationDuringVersionReadDoesNotPublishPartialSnapshotOrConsumeTtl() = runBlocking {
        var now = 0L; var version = 1L; var allowed = true; var revoke = false
        val runtime = ChatCaptureRefreshController(now = { now }, allowed = { allowed },
            loadVersion = { pkg ->
                if (revoke && pkg == "com.ss.android.ugc.aweme") allowed = false
                CaptureAppVersion(version, "v$version")
            }, readCache = { null }, writeCache = { _, _ -> }, fetch = { null })
        runtime.restore("a")
        now = 60_000; version = 2; revoke = true
        runtime.restore("a")
        assertEquals(1L, runtime.version("com.tencent.mm")!!.code)
        assertEquals(1L, runtime.version("com.ss.android.ugc.aweme")!!.code)
        allowed = true; revoke = false
        runtime.restore("a")
        assertEquals(2L, runtime.version("com.tencent.mm")!!.code)
        assertEquals(2L, runtime.version("com.ss.android.ugc.aweme")!!.code)
    }

    private val versionedPolicy = """{"schemaVersion":1,"revision":4,"rules":[
        {"id":"wechat-v1","packageName":"com.tencent.mm","minVersionCode":1,"maxVersionCode":1,"enabled":true,
        "titleIds":[],"inputIds":[],"bodyIds":[],"backLabels":[],"settingsLabels":[],"voiceLabels":[],"voicePosition":"either"},
        {"id":"wechat-v2","packageName":"com.tencent.mm","minVersionCode":2,"maxVersionCode":null,"enabled":true,
        "titleIds":[],"inputIds":[],"bodyIds":[],"backLabels":[],"settingsLabels":[],"voiceLabels":[],"voicePosition":"either"}
    ]}"""

}
