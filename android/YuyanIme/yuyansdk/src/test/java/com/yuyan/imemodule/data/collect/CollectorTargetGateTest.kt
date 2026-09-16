package com.yuyan.imemodule.data.collect

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorTargetGateTest {
    private val online = "https://my.dog8ball.com"
    private val local = "http://127.0.0.1:3000"

    @Test
    fun `online target never depends on usb or local health`() {
        val gate = CollectorTargetGate(online, usbConnected = { false }, localHealthy = { false })

        assertTrue(gate.canUpload(online))
    }

    @Test
    fun `local target is skipped without usb before health is requested`() {
        var healthRequested = false
        val gate = CollectorTargetGate(online, usbConnected = { false }, localHealthy = {
            healthRequested = true
            true
        })

        assertFalse(gate.canUpload(local))
        assertFalse(healthRequested)
    }

    @Test
    fun `local target is skipped when usb exists but health fails`() {
        val gate = CollectorTargetGate(online, usbConnected = { true }, localHealthy = { false })

        assertFalse(gate.canUpload(local))
    }

    @Test
    fun `local target uploads only when usb and health both succeed`() {
        val gate = CollectorTargetGate(online, usbConnected = { true }, localHealthy = { true })

        assertTrue(gate.canUpload(local))
    }

    @Test
    fun `usb link requires both connected and configured`() {
        assertFalse(isUsbDataLink(false, false))
        assertFalse(isUsbDataLink(true, false))
        assertTrue(isUsbDataLink(true, true))
    }

    @Test
    fun `local health requires successful ok response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))
        server.start()
        try {
            val target = server.url("/").toString().trimEnd('/')
            assertTrue(localCollectorHealthy(OkHttpClient(), target))
            assertFalse(localCollectorHealthy(OkHttpClient(), target))
        } finally {
            server.shutdown()
        }
    }
}
