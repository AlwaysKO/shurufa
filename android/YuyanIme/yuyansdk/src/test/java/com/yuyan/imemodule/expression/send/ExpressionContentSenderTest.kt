package com.yuyan.imemodule.expression.send

import android.content.Context
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputContentInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.security.MessageDigest
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionContentSenderTest {
    // 不在 Robolectric 主线程 runBlocking：真实 IO 返回后需要让主 Looper 继续调度。
    private fun runSending(block: suspend CoroutineScope.() -> Unit) {
        val pending = CoroutineScope(Dispatchers.Main).async(block = block)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        try {
            while (!pending.isCompleted && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("发送协程未在超时内结束", pending.isCompleted)
            runBlocking { pending.await() }
        } finally {
            pending.cancel()
        }
    }

    @org.junit.Before
    fun resetProviderPaths() {
        // Robolectric 每个用例换 cacheDir；AndroidX 的静态路径缓存须随之重置。
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache",
        ).clear()
    }

    @Test
    fun `微信 GIF 未声明图片能力时拒绝且绝不把 URI 写入正文`() = runSending {
        val routed = route("com.tencent.mm", "image/gif", accepted = emptyArray())
        assertEquals(ExpressionSendResult.UnsupportedTarget, routed.result)
        assertEquals(0, routed.commits)
        assertNoTextHandoff(routed)
    }

    @Test
    fun `微信只接受静态格式时不能降格发送 GIF`() = runSending {
        val routed = route("com.tencent.mm", "image/gif", accepted = arrayOf("image/png", "image/jpeg"))
        assertEquals(ExpressionSendResult.UnsupportedTarget, routed.result)
        assertEquals(0, routed.commits)
        assertNoTextHandoff(routed)
    }

    @Test
    fun `微信声明 GIF 时经标准内容接口交付原始 MIME 字节与读授权`() = runSending {
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())
            .addExtensionMimeTypeMapping("gif", "image/gif")
        val routed = route("com.tencent.mm", "image/gif", accepted = arrayOf("image/gif"))
        assertEquals(ExpressionSendResult.Sent, routed.result)
        assertEquals(1, routed.commits)
        assertNoTextHandoff(routed)
        val info = requireNotNull(routed.content)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("image/gif", info.description.getMimeType(0))
        assertEquals("image/gif", context.contentResolver.getType(info.contentUri))
        assertEquals(1, routed.contentFlags)
        assertTrue(info.contentUri.lastPathSegment!!.endsWith(".gif"))
        assertArrayEquals(routed.source.readBytes(), context.contentResolver.openInputStream(info.contentUri)!!.use { it.readBytes() })
    }

    @Test
    fun `微信拒绝标准 GIF 内容时不伪报成功或尝试 URI 正文`() = runSending {
        val routed = route("com.tencent.mm", "image/gif", rejectContent = true)
        assertTrue(routed.result is ExpressionSendResult.Failed)
        assertEquals(1, routed.commits)
        assertNoTextHandoff(routed)
    }

    @Test
    fun `非微信 GIF 和微信静态图保持 commit 路径`() = runSending {
        for ((target, mime) in listOf("test.receiver" to "image/gif", "com.tencent.mm" to "image/webp")) {
            val routed = route(target, mime)
            assertEquals(ExpressionSendResult.Sent, routed.result)
            assertEquals(1, routed.commits)
            assertNoTextHandoff(routed)
        }
    }

    private fun assertNoTextHandoff(routed: Routed) {
        assertEquals(null, routed.text)
        assertEquals(null, routed.intent)
        assertEquals(null, routed.grantedUri)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(File(context.cacheDir, "expression/wechat").exists())
    }

    private data class Routed(
        val result: ExpressionSendResult,
        val intent: android.content.Intent?,
        val commits: Int,
        val source: File,
        val text: String?,
        val grantedUri: android.net.Uri?,
        val content: InputContentInfo?,
        val contentFlags: Int,
    )

    private suspend fun route(
        target: String,
        mime: String,
        rejectContent: Boolean = false,
        accepted: Array<String> = arrayOf("image/*"),
    ): Routed {
        val base = ApplicationProvider.getApplicationContext<Context>()
        var opened: android.content.Intent? = null
        var grantedUri: android.net.Uri? = null
        var text: String? = null
        val context = object : android.content.ContextWrapper(base) {
            override fun startActivity(intent: android.content.Intent) {
                opened = intent
            }
            override fun grantUriPermission(toPackage: String, uri: android.net.Uri, flags: Int) {
                grantedUri = uri
            }
        }
        var commits = 0
        var content: InputContentInfo? = null
        var contentFlags = 0
        val connection = object : BaseInputConnection(View(base), false) {
            override fun commitText(value: CharSequence, newCursorPosition: Int): Boolean {
                text = value.toString()
                return true
            }
            override fun commitContent(info: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
                commits++
                content = info
                contentFlags = flags
                return !rejectContent
            }
        }
        val editor = EditorInfo().also {
            it.packageName = target
            EditorInfoCompat.setContentMimeTypes(it, accepted)
        }
        val path = if (mime == "image/gif") "prebuilt/thanks-nuotuan-bow.gif" else "thumbnails/thanks-nuotuan-bow.webp"
        val source = File(base.cacheDir, "expression/routing/$path")
        source.parentFile!!.mkdirs()
        base.assets.open("expression/$path").use { source.writeBytes(it.readBytes()) }
        val sender = ExpressionContentSender(context, { connection }, { accepted }, { editor })
        return Routed(sender.send(PreparedExpression(source, mime)), opened, commits, source, text, grantedUri, content, contentFlags)
    }

    @Test
    fun `发送原始 GIF 的 URI MIME 授权和 debug 诊断保持一致`() = runSending {
        // Robolectric 的 MIME 表默认为空；补齐平台 GIF 映射，不替换 FileProvider。
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())
            .addExtensionMimeTypeMapping("gif", "image/gif")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "expression/diagnostic/prebuilt/thanks-nuotuan-bow.gif")
        source.parentFile!!.mkdirs()
        val bytes = context.assets.open("expression/prebuilt/thanks-nuotuan-bow.gif").use { it.readBytes() }
        source.writeBytes(bytes)
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        var received: InputContentInfo? = null
        var flags = 0
        val connection = object : BaseInputConnection(View(context), false) {
            override fun commitContent(info: InputContentInfo, grantFlags: Int, opts: android.os.Bundle?): Boolean {
                received = info
                flags = grantFlags
                return true
            }
        }
        val editor = EditorInfo().also {
            it.packageName = "test.receiver"
            EditorInfoCompat.setContentMimeTypes(it, arrayOf("image/*"))
        }
        ShadowLog.clear()
        val sender = ExpressionContentSender(context, { connection }, { emptyArray() }, { editor })
        assertEquals(ExpressionSendResult.Sent, sender.send(PreparedExpression(source, "image/gif", "绝不能记录的聊天文字.gif")))
        val info = requireNotNull(received)
        assertEquals("image/gif", info.description.getMimeType(0))
        assertEquals(1, flags)
        assertEquals("image/gif", context.contentResolver.getType(info.contentUri))
        assertArrayEquals(bytes, context.contentResolver.openInputStream(info.contentUri)!!.use { it.readBytes() })
        val logs = ShadowLog.getLogsForTag("ExpressionSendDiag").joinToString("\n") { it.msg }
        assertTrue("应记录原文件 SHA", logs.contains("fileSha256=$expectedSha"))
        assertTrue("应记录 URI 读取 SHA", logs.contains("uriSha256=$expectedSha"))
        assertTrue(logs.contains("fileSize=${bytes.size}"))
        assertTrue(logs.contains("uriSize=${bytes.size}"))
        assertTrue(logs.contains("fileMagic=474946383961"))
        assertTrue(logs.contains("uriMagic=474946383961"))
        assertTrue(logs.contains("resolverMime=image/gif"))
        assertTrue(logs.contains("displayNameSha256="))
        assertTrue(logs.contains("displayExtension=gif"))
        assertFalse(logs.contains("绝不能记录的聊天文字"))
        assertTrue(logs.contains("target=test.receiver"))
        assertTrue(logs.contains("clipMime=image/gif"))
        assertTrue(logs.contains("committed=true"))
        assertTrue(logs.contains("ioMainThread=false"))
    }

    @Test
    fun `诊断读取失败不会阻止原发送调用且不泄露异常路径`() = runSending {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missing = File(context.cacheDir, "expression/绝不能记录的聊天文字.gif")
        missing.delete()
        var committed = false
        val connection = object : BaseInputConnection(View(context), false) {
            override fun commitContent(info: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
                committed = true
                return true
            }
        }
        val editor = EditorInfo().also { EditorInfoCompat.setContentMimeTypes(it, arrayOf("image/*")) }
        ShadowLog.clear()
        val sender = ExpressionContentSender(context, { connection }, { emptyArray() }, { editor })
        assertEquals(ExpressionSendResult.Sent, sender.send(PreparedExpression(missing, "image/gif")))
        assertTrue(committed)
        val logs = ShadowLog.getLogsForTag("ExpressionSendDiag").joinToString("\n") { it.msg }
        assertTrue(logs.contains("diagnosticError=FileNotFoundException"))
        assertTrue(logs.contains("committed=true"))
        assertFalse(logs.contains("绝不能记录的聊天文字"))
    }

    @Test
    fun `非 GIF 保持原发送且不采集诊断`() = runSending {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 不存在的静态文件也不应触发诊断读取；原发送路径只交付 URI。
        val missing = File(context.cacheDir, "expression/not-read.webp")
        missing.delete()
        var committed = false
        val connection = object : BaseInputConnection(View(context), false) {
            override fun commitContent(info: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
                committed = true
                return true
            }
        }
        val editor = EditorInfo().also { EditorInfoCompat.setContentMimeTypes(it, arrayOf("image/*")) }
        ShadowLog.clear()
        val sender = ExpressionContentSender(context, { connection }, { emptyArray() }, { editor })
        assertEquals(ExpressionSendResult.Sent, sender.send(PreparedExpression(missing, "image/webp")))
        assertTrue(committed)
        assertTrue(ShadowLog.getLogsForTag("ExpressionSendDiag").isEmpty())
    }

    @Test
    fun `诊断读取取消向上传播且不执行 commit`() = runSending {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "expression/cancel.gif")
        source.parentFile!!.mkdirs()
        source.writeBytes("GIF89a".toByteArray())
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.expression.fileprovider", source,
        )
        val cancellation = kotlinx.coroutines.CancellationException("不可记录的取消信息")
        // 只在取消测试注入读取异常；成功交付测试使用真实 FileProvider 流。
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : java.io.InputStream() {
                override fun read(): Int = throw cancellation
            },
        )
        var committed = false
        val connection = object : BaseInputConnection(View(context), false) {
            override fun commitContent(info: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
                committed = true
                return true
            }
        }
        val editor = EditorInfo().also { EditorInfoCompat.setContentMimeTypes(it, arrayOf("image/*")) }
        val sender = ExpressionContentSender(context, { connection }, { emptyArray() }, { editor })
        try {
            sender.send(PreparedExpression(source, "image/gif"))
            org.junit.Assert.fail("诊断取消必须传播")
        } catch (actual: kotlinx.coroutines.CancellationException) {
            assertEquals(cancellation.message, actual.message)
        }
        assertFalse(committed)
    }

    @Test
    fun `兼容读取 EditorInfo extras 并接受图片通配 MIME`() {
        val editorInfo = EditorInfo().also {
            EditorInfoCompat.setContentMimeTypes(it, arrayOf("image/*"))
        }

        assertTrue(supportsExpressionMimeType("image/webp", editorInfo) { emptyArray() })
        assertTrue(supportsExpressionMimeType("image/gif", editorInfo) { emptyArray() })
    }

    @Test
    fun `拒绝未声明图片能力的纯文本输入框`() {
        assertFalse(
            supportsExpressionMimeType(
                expressionMimeType = "image/png",
                editorInfo = EditorInfo(),
                fallbackMimeTypes = { arrayOf("text/plain") },
            ),
        )
    }

    @Test
    fun `Android 6 使用兼容 extras 且不读取 API 25 字段`() {
        val editorInfo = EditorInfo().also {
            EditorInfoCompat.setContentMimeTypes(it, arrayOf("image/*"))
        }
        var fallbackCalls = 0

        assertTrue(
            supportsExpressionMimeType("image/webp", editorInfo, sdkInt = 23) {
                fallbackCalls += 1
                error("不应读取 API 25 字段")
            },
        )
        assertTrue(fallbackCalls == 0)
    }
}
