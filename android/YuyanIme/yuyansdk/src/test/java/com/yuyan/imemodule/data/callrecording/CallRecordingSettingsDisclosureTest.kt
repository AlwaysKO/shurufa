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
            assertEquals("输入记忆与上传",activity.title.toString())
            assertTrue(texts.contains("输入记忆上传"))
            assertTrue(texts.contains("确认授权"))
            assertTrue(texts.contains("获取当前输入"))
            assertTrue(texts.contains("wx读取授权"))
            assertTrue(texts.contains("同步最近7天输入记忆"))
            assertTrue(texts.any{it.contains("不能保证正确记忆")})
            assertFalse(texts.any{it.contains("输入记录")||it.contains("输入习惯")})
        } finally { controller.destroy() }
    }

    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
}
