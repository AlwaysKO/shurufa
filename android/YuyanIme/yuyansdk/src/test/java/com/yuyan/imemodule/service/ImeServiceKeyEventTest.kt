package com.yuyan.imemodule.service

import android.content.Context
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.prefs.AppPrefs
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImeServiceKeyEventTest {
    @Test
    fun backKeyBeforeInputViewCreationDoesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        val service = Robolectric.buildService(ImeService::class.java).get()
        ImeService::class.java.getDeclaredField("isSoftKeyboard").apply {
            isAccessible = true
            setBoolean(service, true)
        }

        service.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        service.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun backKeyIsDelegatedToInputMethodServiceFramework() {
        val service = createService()
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null)
        }
        val inputView = unsafe.javaClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, InputView::class.java) as InputView
        ImeService::class.java.getDeclaredField("mInputView").apply {
            isAccessible = true
            set(service, inputView)
        }

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_BACK,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
        )

        assertFalse("返回键应由 InputMethodService 维护跟踪和隐藏生命周期", handled)
    }

    private fun createService(): ImeService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        return Robolectric.buildService(ImeService::class.java).get().also { service ->
            ImeService::class.java.getDeclaredField("isSoftKeyboard").apply {
                isAccessible = true
                setBoolean(service, true)
            }
        }
    }
}
