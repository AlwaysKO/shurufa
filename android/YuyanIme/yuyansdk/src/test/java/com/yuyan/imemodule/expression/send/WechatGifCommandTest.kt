package com.yuyan.imemodule.expression.send

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WechatGifCommandTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun resetProvider() {
        context.getSharedPreferences("expression_delivery", 0).edit().clear().commit()
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache",
        ).clear()
        shadowOf(android.webkit.MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("gif", "image/gif")
    }
    private fun runSending(block: suspend () -> Unit) {
        val task = CoroutineScope(Dispatchers.Main).async { block() }
        try {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!task.isCompleted && System.nanoTime() < deadline) {
                shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertTrue(task.isCompleted)
            runBlocking { task.await() }
        } finally { task.cancel() }
    }
    private data class Delivery(
        val result: ExpressionSendResult, val file: File, val command: String?, val data: Bundle?,
        val granted: Uri?, val recipient: String?, val flags: Int,
    )
    private suspend fun deliver(
        version: String = "8.0.78", capability: Int = 1, compatibleIme: Boolean = true,
        accepted: Boolean = true, throws: Boolean = false, corrupt: Boolean = false, preparedFile: File? = null,
        target: String = "com.tencent.mm",
    ): Delivery {
        val base = context
        shadowOf(base.packageManager).installPackage(PackageInfo().apply {
            packageName = target; versionName = version
            applicationInfo = ApplicationInfo().apply { packageName = target }
        })
        val component = if (compatibleIme)
            "${base.packageName}/com.yuyan.imemodule.compat.com.sohu.inputmethod.sogou.DebugGifImeService"
            else "other.ime/other.ime.Service"
        Settings.Secure.putString(base.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, component)
        var command: String? = null; var data: Bundle? = null
        var uri: Uri? = null; var recipient: String? = null; var flags = 0
        val wrapper = object : ContextWrapper(base) {
            override fun grantUriPermission(toPackage: String, contentUri: Uri, mode: Int) {
                recipient = toPackage; uri = contentUri; flags = mode
            }
            override fun startActivity(intent: Intent) { fail("不能打开分享页") }
        }
        val connection = object : BaseInputConnection(View(base), false) {
            override fun commitText(text: CharSequence, cursor: Int): Boolean = error("不能把URI写入正文")
            override fun commitContent(content: InputContentInfo, flags: Int, opts: Bundle?): Boolean = error("不能回退到静态化路径")
            override fun performPrivateCommand(action: String, payload: Bundle?): Boolean {
                command = action; data = payload
                if (throws) throw IllegalStateException("test failure")
                return accepted
            }
        }
        val editor = EditorInfo().apply {
            packageName = target
            extras = Bundle().apply { putInt("SUPPORT_SOGOU_EXPRESSION", capability) }
            EditorInfoCompat.setContentMimeTypes(this, arrayOf("image/gif"))
        }
        val file = preparedFile ?: File(base.cacheDir, "expression/gif-command/test.gif").apply {
            parentFile!!.mkdirs()
            writeBytes(if (corrupt) "not a gif".toByteArray() else base.assets.open("expression/prebuilt/thanks-nuotuan-bow.gif").use { it.readBytes() })
        }
        val result = ExpressionContentSender(wrapper, { connection }, { arrayOf("image/gif") }, { editor })
            .send(PreparedExpression(file, "image/gif"))
        return Delivery(result, file, command, data, uri, recipient, flags)
    }

    private fun installDeliveryConfig(version: String = "8.0.79", enabled: Boolean = true, target: String = "com.tencent.mm") {
        val document = """{"schemaVersion":1,"revision":1,"rules":[{
            "id":"wechat-gif","packageName":"$target","mimeTypes":["image/gif"],
            "minVersionCode":0,"maxVersionCode":null,"versionName":"$version","minSdk":26,
            "enabled":$enabled,"method":"private_command","requireCompatIme":true,
            "requiredEditorExtras":{"SUPPORT_SOGOU_EXPRESSION":1},
            "action":"com.example.updated.commit","uriKey":"NEW_GIF_URI"
        }]}"""
        val authority = com.yuyan.imemodule.data.collect.ServerConfig.baseUrl
        context.getSharedPreferences("expression_delivery", 0).edit()
            .putString("config:$authority", document).commit()
    }

    @Test fun cachedPolicyUpdatesWechatVersionActionAndUriKeyWithoutRepacking() = runSending {
        installDeliveryConfig()
        val sent = deliver(version = "8.0.79")
        assertEquals(ExpressionSendResult.WechatSubmitted, sent.result)
        assertEquals("com.example.updated.commit", sent.command)
        val uri = sent.data!!.getParcelable<Uri>("NEW_GIF_URI")!!
        assertEquals("image/gif", context.contentResolver.getType(uri))
        assertArrayEquals(sent.file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertNull(sent.data!!.getParcelable<Uri>("EXP_PATH_URI"))
    }

    @Test fun disabledCachedPolicyRejectsWithoutFallbackOrGrant() = runSending {
        installDeliveryConfig(version = "8.0.78", enabled = false)
        val sent = deliver()
        assertEquals(ExpressionSendResult.UnsupportedTarget, sent.result)
        assertNull(sent.command)
        assertNull(sent.granted)
    }

    @Test fun qqAndDouyinPrivateRulesGrantOnlyThatAppAndKeepGifBytes() = runSending {
        for (target in listOf("com.tencent.mobileqq", "com.ss.android.ugc.aweme")) {
            installDeliveryConfig(target = target)
            val sent = deliver(version = "8.0.79", target = target)
            assertEquals(ExpressionSendResult.AppSubmitted, sent.result)
            assertEquals(target, sent.recipient)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, sent.flags)
            val uri = sent.data!!.getParcelable<Uri>("NEW_GIF_URI")!!
            assertArrayEquals(sent.file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun remoteQueryOriginalReachesWechatWithGifMimeAndIdenticalBytes() = runSending {
        val bytes = context.assets.open("expression/prebuilt/thanks-nuotuan-bow.gif").use { it.readBytes() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val cache = com.yuyan.imemodule.expression.ExpressionCache(context.cacheDir)
        File(context.cacheDir, "expression-query/originals/$sha").apply {
            parentFile!!.mkdirs(); writeBytes(bytes)
        }
        val resolver = com.yuyan.imemodule.expression.ExpressionAssetResolver(cache,
            { error("已命中原件缓存") }, { _, _, _, _ -> error("不得重新下载") })
        val file = resolver.resolve("remote-regression", "remote/original.gif", sha, null)!!
        val sent = deliver(preparedFile = file)
        assertEquals(ExpressionSendResult.WechatSubmitted, sent.result)
        val uri = sent.data!!.getParcelable<Uri>("EXP_PATH_URI")!!
        assertEquals("image/gif", context.contentResolver.getType(uri))
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    private fun unsharedGif(): File = File(context.cacheDir, "expression-query/originals/delivery-edge-source").apply {
        parentFile!!.mkdirs()
        context.assets.open("expression/prebuilt/thanks-nuotuan-bow.gif").use { writeBytes(it.readBytes()) }
    }

    @Test fun deliveryCopiesAreReusedWithoutChangingOriginalCache() = runSending {
        val original = unsharedGif()
        val first = deliver(preparedFile = original)
        val second = deliver(preparedFile = original)
        assertEquals(ExpressionSendResult.WechatSubmitted, first.result)
        assertEquals(first.granted, second.granted)
        assertTrue(original.isFile)
        val copies = File(context.cacheDir, "expression/ime-delivery").listFiles()!!.filter { it.isFile }
        assertEquals(1, copies.size)
        assertArrayEquals(original.readBytes(), copies.single().readBytes())
    }

    @Test fun reusingADeliveryFileRefreshesItsReadProtection() = runSending {
        val first = deliver(preparedFile = unsharedGif())
        assertEquals(ExpressionSendResult.WechatSubmitted, first.result)
        val shared = File(context.cacheDir, "expression/ime-delivery/${first.granted!!.lastPathSegment}")
        shared.setLastModified(System.currentTimeMillis() - 2L * 60 * 60 * 1000)
        val before = System.currentTimeMillis()
        val second = deliver(preparedFile = shared)
        assertEquals(ExpressionSendResult.WechatSubmitted, second.result)
        assertTrue("重新交接的文件需要重新获得读取保护", shared.lastModified() >= before - 1000)
    }

    @Test fun deliveryCacheDoesNotEvictRecentHandoffsWhenFull() = runSending {
        val original = unsharedGif()
        val recent = File(context.cacheDir, "expression/ime-delivery/recent.gif").apply { parentFile!!.mkdirs() }
        java.io.RandomAccessFile(recent, "rw").use { it.setLength(64L * 1024 * 1024) }
        recent.setLastModified(System.currentTimeMillis())
        val result = deliver(preparedFile = original)
        assertTrue(result.result is ExpressionSendResult.Failed)
        assertNull(result.command)
        assertNull(result.granted)
        assertTrue("不能淘汰微信可能仍在读取的文件", recent.exists())
    }

    @Test fun deliveryCacheCanEvictOldCopiesWithoutDeletingQueryOriginal() = runSending {
        val original = unsharedGif()
        val old = File(context.cacheDir, "expression/ime-delivery/old.gif").apply { parentFile!!.mkdirs() }
        java.io.RandomAccessFile(old, "rw").use { it.setLength(64L * 1024 * 1024) }
        old.setLastModified(System.currentTimeMillis() - 2L * 60 * 60 * 1000)
        val result = deliver(preparedFile = original)
        assertEquals(ExpressionSendResult.WechatSubmitted, result.result)
        assertFalse(old.exists())
        assertTrue(original.exists())
    }

    @Test fun missingOriginalFailsWithoutUriGrantOrHostCommand() = runSending {
        val result = deliver(preparedFile = File(context.cacheDir, "expression-query/originals/missing"))
        assertTrue(result.result is ExpressionSendResult.Failed)
        assertNull(result.granted)
        assertNull(result.command)
    }

    @Test fun originalGifIsHandedOffAsCommandNotTextOrStaticImage() = runSending {
        val sent = deliver()
        assertEquals(ExpressionSendResult.WechatSubmitted, sent.result)
        assertEquals("com.sogou.inputmethod.exp.commit", sent.command)
        val uri = sent.data!!.getParcelable<Uri>("EXP_PATH_URI")!!
        assertEquals(sent.granted, uri)
        assertEquals("com.tencent.mm", sent.recipient)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, sent.flags)
        assertEquals("image/gif", context.contentResolver.getType(uri))
        assertTrue(uri.lastPathSegment!!.endsWith(".gif"))
        assertArrayEquals(sent.file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun aiComposedGifReachesWechatAsTheActualAnimatedOutput() = runSending {
        val phrase = "谢谢你今天帮忙"
        val template = com.yuyan.imemodule.expression.ExpressionCatalog.fromAssets(context)
            .synthesisTemplates(phrase).first { it.id == "blank-cat-side-eye" }
        val source = File(context.cacheDir, "ai-source.gif").apply {
            context.assets.open("expression/${template.fileName}").use { writeBytes(it.readBytes()) }
        }
        val candidate = com.yuyan.imemodule.expression.ExpressionRecommendationResolver(context.cacheDir) { source }
            .resolveRecommendations(listOf(template), phrase).single()
        val output = com.yuyan.imemodule.expression.render.ExpressionRenderer(context.cacheDir)
            .render(candidate, source, phrase)
        assertNotEquals(source.canonicalPath, output.canonicalPath)
        assertFalse(source.readBytes().contentEquals(output.readBytes()))
        val sent = deliver(preparedFile = output)
        assertEquals(ExpressionSendResult.WechatSubmitted, sent.result)
        val uri = sent.data!!.getParcelable<Uri>("EXP_PATH_URI")!!
        val received = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals("image/gif", context.contentResolver.getType(uri))
        assertArrayEquals(output.readBytes(), received)
        val original = com.bumptech.glide.gifdecoder.GifHeaderParser().setData(source.readBytes()).parseHeader()
        val composed = com.bumptech.glide.gifdecoder.GifHeaderParser().setData(received).parseHeader()
        assertEquals(0, composed.status)
        assertTrue(composed.numFrames > 1)
        assertEquals(original.numFrames, composed.numFrames)
    }

    @Test fun missingCapabilityDoesNotFallBackToLossyStandardRoute() = runSending {
        val result = deliver(capability = 0)
        assertEquals(ExpressionSendResult.UnsupportedTarget, result.result)
        assertNull(result.command); assertNull(result.granted)
    }
    @Test fun unknownWechatVersionDoesNotGuessCompatibility() = runSending {
        val result = deliver(version = "8.0.79")
        assertEquals(ExpressionSendResult.UnsupportedTarget, result.result)
        assertNull(result.command); assertNull(result.granted)
    }
    @Test fun differentImeDoesNotUseGifBranchKnownToBecomeStatic() = runSending {
        val result = deliver(compatibleIme = false)
        assertEquals(ExpressionSendResult.UnsupportedTarget, result.result)
        assertNull(result.command); assertNull(result.granted)
    }
    @Test fun rejectedOrThrowingCommandNeverReportsSentOrResends() = runSending {
        assertTrue(deliver(accepted = false).result is ExpressionSendResult.Failed)
        assertTrue(deliver(throws = true).result is ExpressionSendResult.Failed)
    }
    @Test fun olderAndroidApiDoesNotEnterCommandOrGrantUri() = runSending {
        val original = android.os.Build.VERSION.SDK_INT
        try {
            for (sdk in listOf(23, 25)) {
                // 在当前沙箱核对API路由边界；不冒充对应旧版系统的真机验证。
                org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", sdk)
                val result = deliver(accepted = false)
                assertEquals(ExpressionSendResult.UnsupportedTarget, result.result)
                assertNull(result.command); assertNull(result.granted)
            }
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", original)
        }
    }

    @Test fun nonGifBytesAreNotHandedOff() = runSending {
        val result = deliver(corrupt = true)
        assertTrue(result.result is ExpressionSendResult.Failed)
        assertNull(result.command); assertNull(result.granted)
    }
}
