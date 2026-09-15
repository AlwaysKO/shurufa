package com.yuyan.imemodule.expression.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.bumptech.glide.load.resource.UnitTransformation
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.yuyan.imemodule.expression.ExpressionCatalog
import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressionAssetLifecycleTest {
    @Test
    fun `真实 GIF 随候选 ImageView 滑出 detach 和标签隐藏停止回来恢复`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        val adapter = ExpressionAssetAdapter(onClick = {})
        val holder = adapter.onCreateViewHolder(parent, 0)
        parent.addView(holder.itemView)
        val asset = ExpressionCatalog.fromAssets(activity).recommend("谢谢", 1).single()
        val bytes = activity.assets.open("expression/${asset.fileName}").use { it.readBytes() }
        val decoder = StandardGifDecoder(BitmapProvider, GifHeaderParser().setData(bytes).parseHeader(), ByteBuffer.wrap(bytes))
        decoder.advance()
        val drawable = GifDrawable(activity, decoder, UnitTransformation.get(), 240, 240, requireNotNull(decoder.nextFrame))
        try {
            holder.image.setImageDrawable(drawable)
            drawable.start()
            assertTrue("显式启动后运行", drawable.isRunning)
            parent.removeView(holder.itemView)
            assertFalse("滑出屏幕 detach 停止", drawable.isRunning)
            parent.addView(holder.itemView)
            assertTrue("重新 attach 恢复", drawable.isRunning)
            parent.visibility = View.GONE
            assertFalse("标签隐藏停止", drawable.isRunning)
            parent.visibility = View.VISIBLE
            assertTrue("标签恢复播放", drawable.isRunning)
        } finally {
            holder.image.setImageDrawable(null)
            drawable.stop()
            drawable.recycle()
            controller.pause().stop().destroy()
        }
    }

    private object BitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config) = Bitmap.createBitmap(width, height, config)
        override fun release(bitmap: Bitmap) = bitmap.recycle()
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(array: IntArray) = Unit
    }
}
