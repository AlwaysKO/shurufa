package com.yuyan.imemodule.expression.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.expression.ExpressionCatalog
import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.ExpressionAssetResolver
import com.yuyan.imemodule.expression.ExpressionSync
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import com.yuyan.imemodule.expression.ExpressionCache
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EmojiCombinationPickerTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val catalog = ExpressionCatalog.fromAssets(activity)
    private val picker = EmojiCombinationPicker(activity).also {
        activity.setContentView(it)
        it.render(catalog)
    }
    private val preview get() = picker.findViewById<ImageView>(R.id.expression_emoji_preview)
    private val title get() = picker.findViewById<TextView>(R.id.expression_emoji_title)
    private fun select(combination: EmojiCombination) {
        val list = picker.findViewById<RecyclerView>(R.id.expression_emoji_list)
        @Suppress("UNCHECKED_CAST")
        val adapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        for (id in listOf(combination.firstId, combination.secondId)) {
            val index = catalog.document.emojiBases.sortedBy { it.sortOrder }.indexOfFirst { it.id == id }
            val holder = adapter.createViewHolder(list, 0)
            adapter.bindViewHolder(holder, index)
            holder.itemView.performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun missing() = catalog.document.emojiCombinations.first {
        runCatching { activity.assets.open("expression/${it.fileName}").close() }.isFailure
    }

    @Test fun `下载中禁发失败明确重试且真实交付后点击可发送`() {
        var deliver: ((File?) -> Unit)? = null
        var requests = 0
        var sent: File? = null
        picker.onCombinationMissing = { _, callback -> requests++; deliver = callback }
        picker.onCombinationClick = { _, file -> sent = file }
        select(missing())
        assertFalse(preview.isEnabled)
        assertTrue(title.text.toString().contains("加载"))
        preview.performClick()
        assertNull(sent)
        deliver!!(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(title.text.toString().contains("重试"))
        title.performClick()
        assertEquals(2, requests)
        val file = File.createTempFile("combo", ".webp").apply { writeText("resolved") }
        deliver!!(file)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(preview.isEnabled)
        preview.performClick()
        assertEquals(file, sent)
        file.delete()
    }

    @Test fun `重选同组合时旧下载回调不得覆盖新请求`() {
        val callbacks = mutableListOf<(File?) -> Unit>()
        picker.onCombinationMissing = { _, callback -> callbacks += callback }
        val combo = missing()
        select(combo)
        picker.reset()
        select(combo)
        callbacks[0](File.createTempFile("stale", ".webp").apply { deleteOnExit() })
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(preview.isEnabled)
        assertTrue(title.text.toString().contains("加载"))
    }

    @Test fun `素材解析异常显示失败并允许重试而不发送假组合`() {
        val root = java.nio.file.Files.createTempDirectory("emoji-io-error").toFile()
        var attempts = 0
        var sent = false
        val resolver = ExpressionAssetResolver(ExpressionCache(root), {
            throw java.io.FileNotFoundException()
        }) { _, _, _, _ ->
            attempts++
            throw java.io.IOException("读取素材失败")
        }
        try {
            picker.onCombinationMissing = { value, deliver ->
                deliver(runBlocking { resolver.resolve(value.version, value.fileName, value.sha256, value.url) })
            }
            picker.onCombinationClick = { _, _ -> sent = true }
            select(missing())
            assertFalse(preview.isEnabled)
            assertTrue(title.text.toString().contains("重试"))
            title.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, attempts)
            preview.performClick()
            assertFalse(sent)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `缺URL组合真实下载校验后预览与发送复用同一文件`() = runBlocking {
        val builtin = catalog.document.emojiCombinations.first {
            runCatching { activity.assets.open("expression/${it.fileName}").close() }.isSuccess
        }
        val bytes = activity.assets.open("expression/${builtin.fileName}").use { it.readBytes() }
        val combo = builtin.copy(fileName = "emoji-combinations/download-only.webp", url = null)
        val remoteCatalog = ExpressionCatalog(catalog.document.copy(emojiCombinations = listOf(combo)))
        val root = java.nio.file.Files.createTempDirectory("emoji-download").toFile()
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val cache = ExpressionCache(root)
            val sync = ExpressionSync(OkHttpClient(), server.url("/").toString().trimEnd('/'),
                "test-device", remoteCatalog, cache, this)
            val resolver = ExpressionAssetResolver(cache, { activity.assets.open("expression/$it") }, sync::download)
            picker.render(remoteCatalog)
            picker.onCombinationMissing = { value, deliver ->
                deliver(runBlocking { resolver.resolve(value.version, value.fileName, value.sha256, value.url) })
            }
            var sent: File? = null
            picker.onCombinationClick = { _, file -> sent = file }
            select(combo)
            assertTrue(preview.isEnabled)
            preview.performClick()
            assertNotNull(sent)
            assertArrayEquals(bytes, sent!!.readBytes())
            assertEquals(sent, resolver.resolve(combo.version, combo.fileName, combo.sha256, null))
            assertEquals(1, server.requestCount)
            assertEquals("/uploads/expression/${combo.fileName}", server.takeRequest().path)
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test fun `真正内置组合也经过统一解析并交付真实文件`() {
        val combo = catalog.document.emojiCombinations.first {
            runCatching { activity.assets.open("expression/${it.fileName}").close() }.isSuccess
        }
        var requested: EmojiCombination? = null
        val root = java.nio.file.Files.createTempDirectory("emoji-builtin").toFile()
        val resolver = ExpressionAssetResolver(ExpressionCache(root), {
            activity.assets.open("expression/$it")
        }) { _, _, _, _ -> error("内置不应联网") }
        var sent: File? = null
        picker.onCombinationClick = { _, file -> sent = file }
        picker.onCombinationMissing = { value, deliver ->
            requested = value
            deliver(runBlocking { resolver.resolve(value.version, value.fileName, value.sha256, value.url) })
        }
        select(combo)
        assertEquals(combo, requested)
        assertTrue(preview.isEnabled)
        preview.performClick()
        assertNotNull(sent)
        assertArrayEquals(activity.assets.open("expression/${combo.fileName}").use { it.readBytes() }, sent!!.readBytes())
        root.deleteRecursively()
    }
}
