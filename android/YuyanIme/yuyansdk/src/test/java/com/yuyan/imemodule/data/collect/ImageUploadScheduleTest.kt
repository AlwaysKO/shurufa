package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class ImageUploadScheduleTest {
    private var now = 0L
    private val policy = ImageUploadSchedule { now }
    private val mobile = ImageUploadNetwork.MOBILE
    private val wifi = ImageUploadNetwork.WIFI

    @Test fun `idle starts exactly three seconds after key activity`() {
        policy.noteKeyActivity()
        now = 2999
        assertFalse(policy.isInputIdle())
        assertNull(policy.beginPreparation())
        now = 3000
        assertTrue(policy.isInputIdle())
        policy.beginPreparation()!!.close()
    }

    @Test fun `long touch and nested sources hold idle until final release`() {
        val parent = Any()
        val child = Any()
        policy.noteTouch(0, parent)
        policy.noteTouch(0, child)
        now = 10000
        assertFalse(policy.isInputIdle())
        policy.noteTouch(6, child) // ACTION_POINTER_UP is not final UP
        now += 3000
        assertFalse(policy.isInputIdle())
        policy.noteTouch(1, child)
        now += 3000
        assertFalse(policy.isInputIdle())
        policy.noteTouch(3, parent)
        now += 2999
        assertFalse(policy.isInputIdle())
        now++
        assertTrue(policy.isInputIdle())
    }

    @Test fun `mobile charges actual bytes across rolling window even after failure`() {
        policy.tryStartImage(mobile, 600000)!!.close()
        now = 60000
        policy.tryStartImage(mobile, 400000)!!.close()
        assertEquals(48576L, policy.maxImageBytes(mobile))
        now = 119999
        assertNull(policy.tryStartImage(mobile, 50000))
        now = 120000
        assertEquals(648576L, policy.maxImageBytes(mobile))
        now = 180000
        assertEquals(1048576L, policy.maxImageBytes(mobile))
    }

    @Test fun `oversized image spends no quota and smaller image still proceeds`() {
        assertNull(policy.tryStartImage(mobile, 1048577))
        assertEquals(1048576L, policy.maxImageBytes(mobile))
        policy.tryStartImage(mobile, 100)!!.close()
        assertEquals(1048476L, policy.maxImageBytes(mobile))
    }

    @Test fun `wifi and usb bypass quota without resetting mobile budget`() {
        policy.tryStartImage(mobile, 1048576)!!.close()
        policy.tryStartImage(wifi, 5000000)!!.close()
        policy.tryStartImage(ImageUploadNetwork.USB, 5000000)!!.close()
        assertEquals(0L, policy.maxImageBytes(mobile))
        assertEquals(Long.MAX_VALUE, policy.maxImageBytes(wifi))
        assertEquals(0L, policy.maxImageBytes(ImageUploadNetwork.OFFLINE))
        assertNull(policy.tryStartImage(ImageUploadNetwork.OFFLINE, 1))
    }

    @Test fun `preparation and uploads share one nonblocking permit with idempotent release`() {
        val preparation = policy.beginPreparation()!!
        assertNull(policy.tryStartImage(wifi, 1))
        assertNull(policy.beginPreparation())
        preparation.close()
        val upload = policy.tryStartImage(wifi, 1)!!
        preparation.close()
        assertNull(policy.beginPreparation())
        policy.noteKeyActivity()
        upload.close()
        assertNull(policy.tryStartImage(wifi, 1))
        now = 3000
        policy.tryStartImage(wifi, 1)!!.close()
    }

    @Test fun `only exact loopback target is usb without internet`() {
        assertTrue(ImageUploadSchedule.isUsbTarget("http://127.0.0.1:18080/api"))
        assertTrue(ImageUploadSchedule.isUsbTarget("http://[::1]:18080"))
        assertFalse(ImageUploadSchedule.isUsbTarget("http://127.0.0.1.example.com"))
        assertFalse(ImageUploadSchedule.isUsbTarget("http://127.0.0.1@evil.example"))
        assertFalse(ImageUploadSchedule.isUsbTarget("broken"))
    }
}
