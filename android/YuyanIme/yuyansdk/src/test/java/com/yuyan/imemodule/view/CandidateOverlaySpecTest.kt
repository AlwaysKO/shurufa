package com.yuyan.imemodule.view

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CandidateOverlaySpecTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `关闭图形小于点击区域且覆盖候选列表`() {
        assertEquals(48, CandidateOverlaySpec.touchTargetDp)
        assertEquals(23, CandidateOverlaySpec.iconDp)
        assertTrue(CandidateOverlaySpec.overlapDp > 0)
    }

    @Test
    fun `候选列表横向滚动且关闭按钮悬浮覆盖`() {
        val candidates = RecyclerView(context)
        val action = ImageView(context)
        val overlay = createCandidateOverlay(context, candidates, action)

        assertEquals(LinearLayoutManager.HORIZONTAL, (candidates.layoutManager as LinearLayoutManager).orientation)
        assertFalse(candidates.isNestedScrollingEnabled)
        assertEquals(FrameLayout::class.java, overlay.javaClass)
        assertEquals(overlay, candidates.parent)
        assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, candidates.layoutParams.width)
        assertTrue(action.translationZ > candidates.translationZ)
    }
}
