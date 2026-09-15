package com.yuyan.imemodule.utils

import android.content.ComponentName
import android.provider.Settings
import com.yuyan.imemodule.application.Launcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InputMethodUtilTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "com.yuyan.imemodule.compat.com.sohu.inputmethod.sogou.DebugGifImeService"

    @Before fun setup() {
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
    }

    @Test fun `设置页认可已验证的正常 GIF 入口`() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            ComponentName(context.packageName, name).flattenToShortString())
        assertEquals(name, InputMethodUtil.serviceName)
        assertTrue(InputMethodUtil.isSelected())
    }

    @Test fun `同名服务在别的应用中不能误认为我们的输入法`() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
            ComponentName("test.other", name).flattenToShortString())
        assertFalse(InputMethodUtil.isSelected())
    }
}
