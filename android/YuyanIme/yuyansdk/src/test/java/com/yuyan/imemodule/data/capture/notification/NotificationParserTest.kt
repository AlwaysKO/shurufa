package com.yuyan.imemodule.data.capture.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationParserTest {
    private val parser = NotificationParser()

    @Test
    fun parsesWechatQqAndDouyinTextNotifications() {
        val cases = listOf(
            "com.tencent.mm" to ChatPlatform.WECHAT,
            "com.tencent.mobileqq" to ChatPlatform.QQ,
            "com.ss.android.ugc.aweme" to ChatPlatform.DOUYIN,
        )

        cases.forEach { (packageName, platform) ->
            val parsed = parser.parse(snapshot(packageName, title = "张三", text = "你好"))

            requireNotNull(parsed)
            assertEquals(platform, parsed.conversation.platform)
            assertEquals("张三", parsed.conversation.displayName)
            assertEquals(ConversationType.DIRECT, parsed.conversation.conversationType)
            assertEquals("你好", parsed.message.text)
            assertEquals(ChatMessageType.TEXT, parsed.message.messageType)
            assertEquals("notification", parsed.message.metadata["capture_source"])
        }
    }

    @Test
    fun splitsGroupTitleSenderAndBody() {
        val parsed = parser.parse(snapshot(
            packageName = "com.tencent.mm",
            title = "项目群",
            text = "李四：收到",
            isGroupConversation = true,
        ))

        requireNotNull(parsed)
        assertEquals("项目群", parsed.conversation.displayName)
        assertEquals(ConversationType.GROUP, parsed.conversation.conversationType)
        assertEquals("李四", parsed.message.senderName)
        assertEquals("收到", parsed.message.text)
    }

    @Test
    fun usesMessagingStyleSenderForGroupBody() {
        val parsed = parser.parse(
            snapshot(
                packageName = "com.tencent.mobileqq",
                title = "项目群",
                text = "收到",
                isGroupConversation = true,
                senderName = "李四",
            ),
        )

        requireNotNull(parsed)
        assertEquals("李四", parsed.message.senderName)
        assertEquals("收到", parsed.message.text)
    }

    @Test
    fun missingTitleOrBodyIsSkipped() {
        assertNull(parser.parse(snapshot("com.tencent.mm", title = null, text = "你好")))
        assertNull(parser.parse(snapshot("com.tencent.mm", title = "张三", text = null)))
        assertNull(parser.parse(snapshot("unsupported", title = "张三", text = "你好")))
    }

    @Test
    fun mediaUriIsOnlyReferencedWhenReadable() {
        val readable = parser.parse(snapshot(
            "com.tencent.mobileqq",
            title = "张三",
            text = "[图片]",
            mediaUri = "content://messages/image/1",
            mediaUriReadable = true,
        ))
        val unreadable = parser.parse(snapshot(
            "com.tencent.mobileqq",
            title = "张三",
            text = "[图片]",
            mediaUri = "content://messages/image/2",
            mediaUriReadable = false,
        ))

        requireNotNull(readable)
        requireNotNull(unreadable)
        assertEquals("content://messages/image/1", readable.mediaUri)
        assertNull(unreadable.mediaUri)
        assertEquals(ChatMessageType.IMAGE, readable.message.messageType)
        assertEquals(ChatMessageType.IMAGE, unreadable.message.messageType)
        assertTrue(unreadable.message.metadata.containsKey("asset_capture_failed"))
    }

    @Test
    fun readableNotificationImageIsNormalizedAndStored() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://messages/image/normalized")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(png)
        }
        val importer = NotificationMediaImporter(context)

        assertTrue(importer.canRead(uri))
        val asset = importer.importImage(uri)

        requireNotNull(asset)
        assertEquals("image/png", asset.mimeType)
        assertEquals(32, asset.width)
        assertEquals(24, asset.height)
        assertTrue(File(asset.localPath).isFile)
        File(asset.localPath).delete()
        Unit
    }

    private fun snapshot(
        packageName: String,
        title: String?,
        text: String?,
        isGroupConversation: Boolean = false,
        senderName: String? = null,
        mediaUri: String? = null,
        mediaUriReadable: Boolean = false,
    ) = NotificationSnapshot(
        packageName = packageName,
        notificationKey = "notification-key",
        title = title,
        text = text,
        postedAtMillis = 1_700_000_000_000L,
        isGroupConversation = isGroupConversation,
        senderName = senderName,
        mediaUri = mediaUri,
        mediaUriReadable = mediaUriReadable,
    )
}
