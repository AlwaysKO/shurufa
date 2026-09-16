package com.yuyan.imemodule.data.collect
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class InstallDeviceIdentityTest {
    @Test fun `升级保留旧设备而复制偏好到新手机产生不同身份`() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val old=context.getSharedPreferences(UUID.randomUUID().toString(),0)
        val id=UUID.randomUUID().toString();old.edit().putString("device",id).commit()
        val anchor=File(context.noBackupFilesDir,UUID.randomUUID().toString())
        assertEquals(id,InstallDeviceIdentity.resolve(old,"device",anchor))
        assertEquals(id,InstallDeviceIdentity.resolve(old,"device",anchor))
        val restored=context.getSharedPreferences(UUID.randomUUID().toString(),0)
        restored.edit().putString("device",id).putBoolean(InstallDeviceIdentity.MARKER,true).commit()
        val newAnchor=File(context.noBackupFilesDir,UUID.randomUUID().toString())
        val newId=InstallDeviceIdentity.resolve(restored,"device",newAnchor)
        assertNotEquals(id,newId)
        assertEquals(newId,InstallDeviceIdentity.resolve(restored,"device",newAnchor))
        assertEquals(id,InstallDeviceIdentity.resolve(old,"device",anchor))
    }
}
