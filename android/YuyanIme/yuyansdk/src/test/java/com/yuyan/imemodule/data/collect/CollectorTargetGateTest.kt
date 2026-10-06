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

    @Test fun `offline usb preserves local delivery without opening online target`() {
        val checked = mutableListOf<String>()
        val gate = CollectorTargetGate(online, usbConnected = { true }, localHealthy = { checked.add(it); true }, onlineAvailable = { false })
        assertFalse(gate.canUpload(online))
        assertTrue(gate.canUpload(local))
        org.junit.Assert.assertEquals(listOf(local), checked)
    }

    @Test fun `physical usb keeps explicit offline work eligible`() {
        assertTrue(collectorNetworkAvailable(false) { true })
        assertFalse(collectorNetworkAvailable(false) { false })
        assertTrue(collectorNetworkAvailable(true) { error("online eligibility does not query usb") })
    }

    @Test fun `offline usb does not bypass failed local health`() {
        val gate = CollectorTargetGate(online, usbConnected = { true }, localHealthy = { false }, onlineAvailable = { false })
        assertFalse(gate.canUpload(local))
    }

    @Test fun `offline usb does not probe a historical public target`() {
        var checked = false
        val gate = CollectorTargetGate(online, usbConnected = { true }, localHealthy = { checked = true; true }, onlineAvailable = { false })
        assertFalse(gate.canUpload("https://old.example.com"))
        assertFalse(checked)
    }

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
    fun `honor usb power is accepted when usb state broadcast is missing`() {
        assertTrue(isPhysicalUsbConnected(dataLink = false, usbPowered = true))
        assertTrue(isPhysicalUsbConnected(dataLink = true, usbPowered = false))
        assertFalse(isPhysicalUsbConnected(dataLink = false, usbPowered = false))
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
