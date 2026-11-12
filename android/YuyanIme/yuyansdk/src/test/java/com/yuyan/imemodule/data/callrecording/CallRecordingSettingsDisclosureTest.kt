package com.yuyan.imemodule.data.callrecording

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.yuyan.imemodule.ui.activity.CallRecordingSettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingSettingsDisclosureTest {
    @Test fun `设置页明确录音及通话记录范围并提供旧授权升级入口`() {
        val controller=Robolectric.buildActivity(CallRecordingSettingsActivity::class.java)
        val activity=controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        controller.create()
        try {
            val texts=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().map{it.text.toString()}.toList()
            assertEquals("输入记录2",activity.title.toString())
            assertTrue(texts.contains("输入记录2"))
            assertTrue(texts.contains("确认或扩展授权"))
            assertTrue(texts.contains("授权并立即查找"))
            assertTrue(texts.any{it.contains("最近30天")&&it.contains("超过64MiB仅Wi-Fi")&&it.contains("不恢复或删除原件")})

        } finally { controller.destroy() }
    }

    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
}
