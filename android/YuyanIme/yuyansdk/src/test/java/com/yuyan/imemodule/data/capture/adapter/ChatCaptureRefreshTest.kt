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
        now = 300_000
        runtime.refresh("a")
        assertEquals(4, loads)
        assertEquals(2L, runtime.version("com.tencent.mm")!!.code)
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

}
