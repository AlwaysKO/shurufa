package com.yuyan.imemodule.data.collect

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRegistrationGateTest {
    @Test
    fun `concurrent registration attempts allow only one winner`() {
        val gate = LocationRegistrationGate()
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val winners = AtomicInteger()
        val executor = Executors.newFixedThreadPool(16)

        repeat(16) {
            executor.execute {
                ready.countDown()
                start.await()
                if (gate.tryStart()) winners.incrementAndGet()
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(1, winners.get())
    }

    @Test
    fun `reset permits a later registration`() {
        val gate = LocationRegistrationGate()

        assertTrue(gate.tryStart())
        gate.reset()
        assertTrue(gate.tryStart())
    }
}
