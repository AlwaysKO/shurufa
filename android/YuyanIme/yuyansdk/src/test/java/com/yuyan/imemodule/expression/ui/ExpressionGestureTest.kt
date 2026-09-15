package com.yuyan.imemodule.expression.ui

import android.content.Context
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.expression.model.ExpressionAsset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionGestureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `长按图片只触发展开而不误触点击`() {
        var clicks = 0
        var longPresses = 0
        val adapter = ExpressionAssetAdapter(
            onClick = { clicks += 1 },
            onLongPress = { longPresses += 1 },
        )
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.submitList(listOf(asset()))
        adapter.onBindViewHolder(holder, 0)

        holder.itemView.performLongClick()

        assertEquals(1, longPresses)
        assertEquals(0, clicks)
    }

    @Test
    fun `轻点候选图片只触发一次发送回调`() {
        var selected: ExpressionAsset? = null
        val adapter = ExpressionAssetAdapter(onClick = { selected = it })
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = adapter.onCreateViewHolder(parent, 0)
        val asset = asset()
        adapter.submitList(listOf(asset))
        adapter.onBindViewHolder(holder, 0)

        assertEquals(true, holder.itemView.performClick())
        assertEquals(asset, selected)
    }

    @Test
    fun `普通横向滑动不触发展开`() {
        var longPresses = 0
        val adapter = ExpressionAssetAdapter(
            onClick = {},
            onLongPress = { longPresses += 1 },
        )
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.submitList(listOf(asset()))
        adapter.onBindViewHolder(holder, 0)
        val downTime = 1L

        holder.itemView.dispatchTouchEvent(MotionEvent.obtain(downTime, 1L, MotionEvent.ACTION_DOWN, 80f, 50f, 0))
        holder.itemView.dispatchTouchEvent(MotionEvent.obtain(downTime, 20L, MotionEvent.ACTION_MOVE, 10f, 50f, 0))
        holder.itemView.dispatchTouchEvent(MotionEvent.obtain(downTime, 30L, MotionEvent.ACTION_UP, 10f, 50f, 0))

        assertEquals(0, longPresses)
    }

    private fun asset() = ExpressionAsset(
        id = "hello",
        type = "prebuilt",
        format = "webp",
        version = "v1",
        fileName = "prebuilt/hello.webp",
        thumbnailFileName = "thumbnails/hello.webp",
        sha256 = "a".repeat(64),
        width = 512,
        height = 512,
    )
}
