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
            assertEquals("通话录音与上传",activity.title.toString())
            assertTrue(texts.contains("电话 / 微信录音与上传"))
            assertTrue(texts.contains("确认或扩展电话 / 微信自录授权"))
            assertTrue(texts.contains("录制当前微信通话"))
            assertTrue(texts.contains("微信通话识别：通知读取授权"))
            assertTrue(texts.contains("同步最近7天手机通话记录"))
            assertTrue(texts.any{it.contains("不能保证录到双方声音")})
            assertFalse(texts.any{it.contains("输入记录")||it.contains("输入习惯")})
        } finally { controller.destroy() }
    }

    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
}
