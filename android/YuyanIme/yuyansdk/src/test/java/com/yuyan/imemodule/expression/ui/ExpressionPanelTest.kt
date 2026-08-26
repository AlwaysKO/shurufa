package com.yuyan.imemodule.expression.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.R
import com.yuyan.imemodule.expression.ExpressionCatalog
import com.yuyan.imemodule.expression.ExpressionPanelState
import com.yuyan.imemodule.expression.ExpressionPanelTab
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionPanelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalog = ExpressionCatalog(
        ExpressionCatalogDocument(
            version = "v1",
            templates = emptyList(),
            emojiBases = emptyList(),
            emojiCombinations = emptyList(),
        ),
    )

    @Test
    fun `紧凑态显示横向单行且推荐标签具有选中标记`() {
        val panel = ExpressionPanel(context)
        val state = visibleState()

        panel.render(state, catalog)

        val recommended = panel.findViewById<TextView>(R.id.expression_tab_recommended)
        val templates = panel.findViewById<TextView>(R.id.expression_tab_templates)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)
        assertTrue(recommended.isSelected)
        assertFalse(templates.isSelected)
        assertNotNull(recommended.background)
        assertTrue(list.layoutManager is LinearLayoutManager)
        assertEquals(
            RecyclerView.HORIZONTAL,
            (list.layoutManager as LinearLayoutManager).orientation,
        )
    }

    @Test
    fun `展开态显示三列纵向网格且标签选中状态唯一`() {
        val panel = ExpressionPanel(context)
        val state = visibleState().apply {
            selectTab(ExpressionPanelTab.AI_SYNTHESIS)
            expand()
        }

        panel.render(state, catalog)

        val recommended = panel.findViewById<TextView>(R.id.expression_tab_recommended)
        val templates = panel.findViewById<TextView>(R.id.expression_tab_templates)
        val emoji = panel.findViewById<TextView>(R.id.expression_tab_emoji)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)
        assertFalse(recommended.isSelected)
        assertTrue(templates.isSelected)
        assertFalse(emoji.isSelected)
        assertNotNull(templates.background)
        assertTrue(list.layoutManager is GridLayoutManager)
        assertEquals(3, (list.layoutManager as GridLayoutManager).spanCount)
    }

    @Test
    fun `开启时显示搜狗式标签和操作入口`() {
        val panel = ExpressionPanel(context)

        panel.render(visibleState(), catalog)

        assertEquals("推荐", panel.findViewById<TextView>(R.id.expression_tab_recommended).text.toString())
        assertEquals("AI合成", panel.findViewById<TextView>(R.id.expression_tab_templates).text.toString())
        assertEquals("Emoji合成", panel.findViewById<TextView>(R.id.expression_tab_emoji).text.toString())
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_more).visibility)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_close).visibility)
        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_enable).visibility)
    }

    @Test
    fun `关闭时保留空工具行和右侧 AI斗图入口`() {
        val panel = ExpressionPanel(context)
        val state = visibleState().apply { setAiStickerEnabled(false) }

        panel.render(state, catalog)

        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_tab_recommended).visibility)
        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_content).visibility)
        val enable = panel.findViewById<TextView>(R.id.expression_enable)
        assertEquals(View.VISIBLE, enable.visibility)
        assertEquals("AI斗图", enable.text.toString())
    }

    @Test
    fun `展开态素材项保持固定正方形`() {
        val adapter = ExpressionAssetAdapter {}
        val parent = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 3)
        }
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.setExpanded(true)
        adapter.submitList(listOf(asset()))

        adapter.onBindViewHolder(holder, 0)

        assertTrue(holder.itemView.layoutParams.width > 0)
        assertEquals(holder.itemView.layoutParams.width, holder.itemView.layoutParams.height)
    }

    @Test
    fun `GIF 也优先使用缩略图预览`() {
        val gif = asset().copy(
            format = "gif",
            fileName = "templates/a.gif",
            thumbnailFileName = "thumbnails/a.webp",
        )

        assertEquals(
            "file:///android_asset/expression/thumbnails/a.webp",
            previewSource(gif),
        )
    }

    @Test
    fun `长按推荐列表请求展开`() {
        val panel = ExpressionPanel(context)
        var expansions = 0
        panel.onExpandRequested = { expansions += 1 }

        panel.findViewById<RecyclerView>(R.id.expression_asset_list).performLongClick()

        assertEquals(1, expansions)
    }

    private fun visibleState() = ExpressionPanelState().apply {
        beginQuery("你好", requestId = 1)
        applyResults(1, listOf(asset()))
    }

    private fun asset() = ExpressionAsset(
        id = "hello",
        type = "prebuilt",
        format = "webp",
        version = "v1",
        fileName = "templates/hello.webp",
        sha256 = "a".repeat(64),
        width = 512,
        height = 512,
    )
}
