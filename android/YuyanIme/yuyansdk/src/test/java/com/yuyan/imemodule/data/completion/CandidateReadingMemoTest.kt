package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class CandidateReadingMemoTest {
    @Test fun `main and background paging share one safely published bounded memo`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val memo = CandidateReadingMemo { _, reading ->
            calls.incrementAndGet()
            Thread.sleep(20)
            reading
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val ready = java.util.concurrent.CountDownLatch(8)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val work = (1..8).map {
                pool.submit<String> { ready.countDown(); start.await(); memo.normalize("益阳", "yi yang")!! }
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            work.forEach { assertEquals("yi yang", it.get(5, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun `hot path regex patterns are compiled once on singleton initialization`() {
        fun count(type: Class<*>) = type.declaredFields.count { it.type == Regex::class.java }
        assertEquals(2, count(PersonalWordReading::class.java))
        assertEquals(3, count(InputSpellingMatch::class.java))
        assertEquals(3, count(com.yuyan.inputmethod.util.T9Spelling::class.java))
    }

    @Test fun `one refresh reuses valid and invalid normalization with text sensitive keys`() {
        var calls = 0
        val memo = CandidateReadingMemo { text, reading -> calls++; PersonalWordReading.normalize(text, reading) }
        repeat(4) {
            assertEquals("yi yang", memo.normalize("益阳", " YI'YANG "))
            assertNull(memo.normalize("益", " YI'YANG "))
        }
        assertEquals(2, calls)
    }
    @Test fun `bounded memo never drops results and new refresh recalculates`() {
        var calls = 0
        val normalize: (String, String) -> String? = { _, reading -> calls++; reading }
        val memo = CandidateReadingMemo(normalize)
        repeat(600) { assertEquals("r$it", memo.normalize("字", "r$it")) }
        memo.normalize("字", "r0")
        assertEquals(600, calls)
        memo.normalize("字", "r599")
        assertEquals(601, calls)
        CandidateReadingMemo(normalize).normalize("字", "r0")
        assertEquals(602, calls)
    }
}
