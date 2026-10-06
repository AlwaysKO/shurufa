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

    @Test fun `wifi charges bytes and pauses between images and at rolling window limit`() {
        policy.tryStartImage(wifi,4*1024*1024)!!.close()
        assertEquals(0L,policy.maxImageBytes(wifi))
        now=3000
        policy.tryStartImage(wifi,4*1024*1024)!!.close()
        now=6000
        assertEquals(0L,policy.maxImageBytes(wifi))
        now=60000
        assertEquals(4*1024*1024L,policy.maxImageBytes(wifi))
        now=63000
        assertEquals(8*1024*1024L,policy.maxImageBytes(wifi))
    }

    @Test fun `non wifi always pauses and oversized image does not spend quota`() {
        for(network in listOf(mobile,ImageUploadNetwork.USB,ImageUploadNetwork.OFFLINE)) {
            assertEquals(0L,policy.maxImageBytes(network))
            assertNull(policy.tryStartImage(network,1))
        }
        assertNull(policy.tryStartImage(wifi,8*1024*1024+1))
        assertEquals(8*1024*1024L,policy.maxImageBytes(wifi))
    }

    @Test fun `one preparation can proceed during slow upload without allowing a second upload`() {
        val preparation = policy.beginPreparation()!!
        assertNull(policy.tryStartImage(wifi, 1))
        assertNull(policy.beginPreparation())
        preparation.close()
        val upload = policy.tryStartImage(wifi, 1)!!
        preparation.close()
        val duringUpload = policy.beginPreparation()
        assertNotNull("慢上传不能阻止新截图准备", duringUpload)
        assertNull(policy.beginPreparation())
        now = 3000
        assertNull(policy.tryStartImage(wifi, 1))
        duringUpload!!.close()
        assertNull(policy.tryStartImage(wifi, 1))
        policy.noteKeyActivity()
        assertNull(policy.beginPreparation())
        upload.close()
        assertNotNull(policy.tryStartImage(wifi, 1)?.also { it.close() })
        now += 3000
        policy.tryStartImage(wifi, 1)!!.close()
    }

    @Test fun `only exact loopback target is usb without internet`() {
        assertTrue(ImageUploadSchedule.isUsbTarget("http://127.0.0.1:18080/api"))
        assertTrue(ImageUploadSchedule.isUsbTarget("http://[::1]:18080"))
        assertFalse(ImageUploadSchedule.isUsbTarget("http://127.0.0.1.example.com"))
        assertFalse(ImageUploadSchedule.isUsbTarget("http://127.0.0.1@evil.example"))
        assertFalse(ImageUploadSchedule.isUsbTarget("broken"))
    }
    @Test fun `screen off drains faster but switching screen modes shares quota and concurrency`() {
        assertEquals(8*1024*1024L, policy.maxImageBytes(wifi))
        assertEquals(32*1024*1024L, policy.maxImageBytes(wifi, screenOff = true))
        val permit = policy.tryStartImage(wifi, 4*1024*1024, screenOff = true)!!
        now = 1000
        assertNull(policy.tryStartImage(wifi, 1, screenOff = true))
        permit.close()
        assertEquals(28*1024*1024L, policy.maxImageBytes(wifi, screenOff = true))
        assertEquals(0L, policy.maxImageBytes(wifi))
        now = 3000
        assertEquals(4*1024*1024L, policy.maxImageBytes(wifi))
        policy.noteKeyActivity()
        assertNotNull(policy.tryStartImage(wifi, 1, screenOff = true)?.also { it.close() })
        now += 3000
        assertNotNull(policy.tryStartImage(wifi, 1, screenOff = true)?.also { it.close() })
    }
}
