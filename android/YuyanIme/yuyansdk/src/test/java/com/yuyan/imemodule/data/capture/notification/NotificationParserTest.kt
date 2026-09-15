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
    @Test
    fun readableVideoThumbnailDoesNotChangeVideoIntoImage() {
        val parsed = NotificationParser().parse(
            NotificationSnapshot(
                packageName = "com.tencent.mm",
                notificationKey = "video",
                title = "小周",
                text = "[视频]",
                postedAtMillis = 1L,
                mediaUri = "content://wechat/thumbnail",
                mediaUriReadable = true,
            ),
        )

        assertEquals(ChatMessageType.VIDEO, parsed?.message?.messageType)
        assertEquals("content://wechat/thumbnail", parsed?.mediaUri)
    }
    private val parser = NotificationParser()

    @Test
    fun parsesWechatQqAndDouyinTextNotifications() {
        val cases = listOf(
            "com.tencent.mm" to ChatPlatform.WECHAT,
            "com.tencent.mobileqq" to ChatPlatform.QQ,
            "com.ss.android.ugc.aweme" to ChatPlatform.DOUYIN,
        )

        cases.forEach { (packageName, platform) ->
            val parsed = parser.parse(snapshot(
                packageName,
                title = "张三",
                text = "你好",
                isMessagingStyle = packageName == "com.ss.android.ugc.aweme",
            ))

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
    fun skipsDouyinLiveAndPromotionNotificationsOutsideMessagingStyle() {
        val livePromotion = snapshot(
            packageName = "com.ss.android.ugc.aweme",
            title = "松花蛋有活动了",
            text = "丽颖好物8084",
            isMessagingStyle = false,
        )
        assertNull(parser.parse(livePromotion))
        assertTrue(!parser.requiresScreenshotFallback(livePromotion))
        assertEquals("你好", parser.parse(snapshot(
            packageName = "com.ss.android.ugc.aweme",
            title = "张三",
            text = "你好",
            isMessagingStyle = true,
        ))?.message?.text)
    }

    @Test
    fun hiddenDouyinMessageWaitsForScreenshotWhileVisibleMessageKeepsExistingParsing() {
        val hidden = snapshot(
            packageName = "com.ss.android.ugc.aweme",
            title = "抖音",
            text = "你收到一条新消息",
            isMessagingStyle = false,
        )
        val visible = snapshot(
            packageName = "com.ss.android.ugc.aweme",
            title = "张三",
            text = "你好",
            senderName = "张三",
            isMessagingStyle = true,
        )

        assertTrue(parser.requiresScreenshotFallback(hidden))
        assertNull(parser.parse(hidden))
        assertTrue(!parser.requiresScreenshotFallback(visible))
        assertEquals("你好", parser.parse(visible)?.message?.text)
    }

    @Test
    fun genericDouyinPromotionDoesNotTriggerChatScreenshot() {
        val promotion = snapshot(
            packageName = "com.ss.android.ugc.aweme",
            title = "抖音",
            text = "你关注的朋友发布了新作品",
            isMessagingStyle = false,
        )

        assertTrue(!parser.requiresScreenshotFallback(promotion))
        assertNull(parser.parse(promotion))
    }

    @Test
    fun wechatCallStateRequestsOneSupportingScreenshotAndKeepsVideoType() {
        val snapshot = snapshot("com.tencent.mm", "龚林莉", "视频通话中")

        assertTrue(parser.requiresMediaScreenshotFallback(snapshot))
        assertEquals(ChatMessageType.VIDEO, parser.parse(snapshot)?.message?.messageType)
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
    fun genericWechatSummaryWithoutSenderOrContentIsSkipped() {
        assertNull(parser.parse(snapshot("com.tencent.mm", title = "微信", text = "1个联系人发来1条消息")))
        assertNull(parser.parse(snapshot("com.tencent.mm", title = "微信", text = "2 个联系人发来 3 条消息")))
        assertNull(parser.parse(snapshot("com.tencent.mm", title = "微信", text = "[有人@我]1个联系人发来1条消息")))
    }

    @Test
    fun onlyHiddenWechatAggregateRequestsScreenshotFallback() {
        assertTrue(parser.requiresScreenshotFallback(snapshot("com.tencent.mm", "微信", "1个联系人发来1条消息")))
        assertTrue(parser.requiresScreenshotFallback(snapshot("com.tencent.mm", "微信", "张三：你好", summaryText = "2个联系人给你发来了3条新消息。")))
        assertTrue(parser.requiresScreenshotFallback(snapshot("com.tencent.mm", "微信", "你收到了一条新消息！")))
        assertTrue(parser.requiresScreenshotFallback(snapshot("com.tencent.mm", "微信", "[有人@我]1个联系人发来1条消息")))
        assertTrue(parser.requiresScreenshotFallback(snapshot("com.tencent.mm", "微信", "龚林莉邀请你视频通话")))
        assertTrue(!parser.requiresScreenshotFallback(snapshot("com.tencent.mm", "张三", "你好")))
        assertTrue(!parser.requiresScreenshotFallback(snapshot("com.tencent.mobileqq", "QQ", "1个联系人发来1条消息")))
    }

    @Test
    fun messagingStyleContentIsStillCapturedWhenAppTitleIsWechat() {
        val snapshot = snapshot(
            packageName = "com.tencent.mm",
            title = "微信",
            text = "真实聊天内容",
            senderName = "张三",
            isMessagingStyle = true,
            sourceMessageTimestampMillis = 1_700_000_000_000L,
        )

        assertTrue(!parser.requiresScreenshotFallback(snapshot))
        val parsed = parser.parse(snapshot)
        assertEquals("张三", parsed?.conversation?.displayName)
        assertEquals("张三", parsed?.message?.senderName)
        assertEquals("真实聊天内容", parsed?.message?.text)
    }

    @Test
    fun ignoresWechatDesktopLoginNotificationsInsteadOfUploadingOrTakingChatScreenshot() {
        listOf("登录 Windows 微信", "登录 Mac 微信").forEach { text ->
            val snapshot = snapshot("com.tencent.mm", "微信", text)
            assertTrue(parser.shouldIgnore(snapshot))
            assertTrue(!parser.requiresScreenshotFallback(snapshot))
            assertNull(parser.parse(snapshot))
        }
    }

    @Test
    fun unreadableWechatVoiceVideoAndEmojiRequestSupportingScreenshot() {
        listOf("[语音]", "[视频]", "[视频号] 分享", "[表情]", "[动画表情]").forEach { text ->
            assertTrue(parser.requiresMediaScreenshotFallback(snapshot("com.tencent.mm", "张三", text)))
        }
        assertTrue(!parser.requiresMediaScreenshotFallback(snapshot("com.tencent.mm", "张三", "你好")))
        assertTrue(!parser.requiresMediaScreenshotFallback(snapshot(
            "com.tencent.mm",
            "张三",
            "[图片]",
            mediaUri = "content://image/1",
            mediaUriReadable = true,
        )))
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
    fun classifiesWechatIncomingContentPlaceholders() {
        val cases = mapOf(
            "[语音]" to ChatMessageType.VOICE,
            "[图片]" to ChatMessageType.IMAGE,
            "[视频]" to ChatMessageType.VIDEO,
            "[视频号] 凤凰卫视的视频" to ChatMessageType.VIDEO,
            "[文件] 报价单.pdf" to ChatMessageType.FILE,
            "[链接] 新闻" to ChatMessageType.LINK,
            "[位置]" to ChatMessageType.LOCATION,
            "[名片] 张三" to ChatMessageType.CONTACT,
            "[小程序] 商城" to ChatMessageType.MINI_APP,
            "[红包]" to ChatMessageType.RED_PACKET,
            "[转账]" to ChatMessageType.TRANSFER,
        )

        cases.forEach { (text, expected) ->
            assertEquals(expected, parser.parse(snapshot("com.tencent.mm", "张三", text))?.message?.messageType)
        }
    }

    @Test
    fun notificationCreationTimeDistinguishesSeparateIdenticalMessages() {
        val first = parser.parse(snapshot("com.tencent.mm", "张三", "收到", postedAtMillis = 1_700_000_000_000L))
        val second = parser.parse(snapshot("com.tencent.mm", "张三", "收到", postedAtMillis = 1_700_000_001_000L))

        requireNotNull(first)
        requireNotNull(second)
        assertTrue(first.message.displayedTime != second.message.displayedTime)
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
        postedAtMillis: Long = 1_700_000_000_000L,
        summaryText: String? = null,
        isMessagingStyle: Boolean = false,
        sourceMessageTimestampMillis: Long? = null,
    ) = NotificationSnapshot(
        packageName = packageName,
        notificationKey = "notification-key",
        title = title,
        text = text,
        postedAtMillis = postedAtMillis,
        isGroupConversation = isGroupConversation,
        senderName = senderName,
        mediaUri = mediaUri,
        mediaUriReadable = mediaUriReadable,
        summaryText = summaryText,
        isMessagingStyle = isMessagingStyle,
        sourceMessageTimestampMillis = sourceMessageTimestampMillis,
    )
}
