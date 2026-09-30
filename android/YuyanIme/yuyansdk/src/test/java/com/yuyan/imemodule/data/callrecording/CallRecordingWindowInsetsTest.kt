package com.yuyan.imemodule.data.callrecording

import android.content.Context
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.ui.activity.applyCallRecordingWindowInsets
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingWindowInsetsTest {
    @Test fun `状态栏导航栏安全边距作用于实际容器且重复分发不累加`() {
        val root=ScrollView(ApplicationProvider.getApplicationContext<Context>())
        applyCallRecordingWindowInsets(root)
        val insets=WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(10,96,12,48)).build()
        repeat(2){ViewCompat.dispatchApplyWindowInsets(root,insets)}
        assertEquals(96,root.paddingTop)
        assertEquals(48,root.paddingBottom)
        assertEquals(10,root.paddingLeft)
        assertEquals(12,root.paddingRight)
        ViewCompat.dispatchApplyWindowInsets(root,WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(),Insets.NONE).build())
        assertEquals(0,root.paddingTop)
        assertEquals(0,root.paddingBottom)
    }
}
