package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.adapter.ChatAppAdapter
import com.yuyan.imemodule.data.capture.adapter.ParseResult
import com.yuyan.imemodule.data.capture.adapter.ParsedViewport
import com.yuyan.imemodule.data.capture.adapter.SkipReason
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.media.MediaAssetCapturer
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.model.stableKeyOrNull
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCoordinatorTest {
    private val snapshot = UiNodeSnapshot(null, "root", null, null, IntRect(0, 0, 100, 100), emptyList())
    private val conversation = CapturedConversation(
        platform = ChatPlatform.WECHAT,
        accountKey = "account",
        externalKey = "peer",
        displayName = "对方",
        conversationType = ConversationType.DIRECT,
        identityConfidence = 0.95,
    )

    @Test
    fun parsedScreenshotMustKeepItsOriginalTokenAfterScrollingStops() = runBlocking {
        val store = FakeStore()
        val worker = CaptureCoordinator(store = store, deviceId = { "device" }, wakeUploader = {},
            captureGeneration = { 2L })
        assertEquals(CapturePersistResult.FAILED, worker.captureParsed(conversation,
            listOf(message("normal", "18:30")), captureToken = 1L))
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun oldFrameCannotPersistAfterScrollStartedAndAlreadyStopped() = runBlocking {
        var generation = 1L
        val adapter = FakeAdapter(success(mediaMessage(IntRect(10, 10, 70, 70))))
        val store = FakeStore()
        val worker = CaptureCoordinator(adapterForPackage = { adapter }, store = store,
            deviceId = { "device" }, wakeUploader = {}, captureGeneration = { generation },
            mediaCapturer = MediaAssetCapturer { _, _, _ ->
                generation++ // 返回时已恢复允许采集，但该图片属于滚动前的旧请求。
                mapOf(0 to pendingAsset("stale"))
            })
        worker.capture(adapter.packageName, snapshot, 7)
        assertTrue(store.pending.isEmpty())
        assertTrue(store.assets.isEmpty())
    }

    @Test fun qqNotificationScreenshotUsesSamePendingIsolationAndDedupRules() = runBlocking {
        val store = FakeStore()
        val coordinator = coordinator(FakeAdapter(success()), store)
        val pending = conversation.copy(platform = ChatPlatform.QQ, accountKey = "notification-screenshot",
            externalKey = "notification-fallback-v2:" + "a".repeat(64), identityConfidence = 0.55, displayName = "待确认截图")
        val shot = mediaMessage(IntRect(0, 0, 100, 80)).copy(mediaBounds = null, metadata = mapOf(
            "capture_source" to "notification_screenshot_fallback", "source_package" to "com.tencent.mobileqq",
            "notification_key" to "thread", "conversation_identity_status" to "pending"))
        assertEquals(CapturePersistResult.INSERTED, coordinator.captureParsed(pending, listOf(shot), mapOf(0 to pendingAsset("qq-fallback"))))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, coordinator.captureParsed(pending, listOf(shot), mapOf(0 to pendingAsset("qq-fallback"))))
        assertEquals(CapturePersistResult.FAILED, coordinator.captureParsed(pending, listOf(shot.copy(metadata = shot.metadata + ("source_package" to "com.tencent.mm"))), mapOf(0 to pendingAsset("wrong"))))
    }

    @Test
    fun sensitiveTextNeverReachesPersistentCaptureQueue() = runBlocking {
        val adapter = FakeAdapter(success(message("验证码 123456", "18:30"), message("123456", "18:31"), message("正常聊天", "18:32")))
        val store = FakeStore()
        coordinator(adapter,store).capture("com.tencent.mm", snapshot)
        assertEquals(1,store.pending.size)
        assertTrue(store.pending.first().payloadJson.contains("正常聊天"))
    }

    @Test
    fun sensitiveTextNeverReachesActiveViewportCallback() = runBlocking {
        val adapter = FakeAdapter(success(message("验证码 123456", "18:30"), message("正常聊天", "18:31")))
        val observed = mutableListOf<String?>()
        val coordinator = CaptureCoordinator(
            adapterForPackage = { adapter },
            store = FakeStore(),
            deviceId = { "00000000-0000-4000-8000-000000000001" },
            wakeUploader = {},
            onViewportParsed = { viewport -> observed += viewport.messages.map { it.text } },
        )

        coordinator.capture("com.tencent.mm", snapshot)

        assertEquals(listOf("正常聊天"), observed)
    }

    @Test
    fun firstViewportWritesMessagesAndSecondIdenticalViewportDoesNot() = runBlocking {
        val adapter = FakeAdapter(success(message("第一条", "18:30")))
        val store = FakeStore()
        var wakes = 0
        val coordinator = coordinator(adapter, store) { wakes += 1 }

        coordinator.capture("com.tencent.mm", snapshot)
        coordinator.capture("com.tencent.mm", snapshot)

        assertEquals(1, store.pending.size)
        assertEquals(1, store.seen.size)
        assertEquals(1, wakes)
    }

    @Test
    fun overlappingViewportsOnlyWriteNewMessages() = runBlocking {
        val first = message("第一条", "18:30")
        val overlap = message("重叠消息", "18:31")
        val third = message("第三条", "18:32")
        val adapter = FakeAdapter(success(first, overlap))
        val store = FakeStore()
        val coordinator = coordinator(adapter, store)

        coordinator.capture("com.tencent.mm", snapshot)
        adapter.result = success(overlap, third)
        coordinator.capture("com.tencent.mm", snapshot)

        assertEquals(3, store.pending.size)
    }

    @Test
    fun twoRealMessagesWithSameTextAreBothKept() = runBlocking {
        val adapter = FakeAdapter(success(
            message("相同文本", "18:30", ordinal = 0),
            message("相同文本", "18:30", ordinal = 1),
        ))
        val store = FakeStore()

        coordinator(adapter, store).capture("com.tencent.mm", snapshot)

        assertEquals(2, store.pending.size)
        assertEquals(2, store.pending.map { it.fingerprint }.distinct().size)
    }

    @Test
    fun skipResultDoesNotWriteDatabase() = runBlocking {
        val adapter = FakeAdapter(ParseResult.Skip(SkipReason.AMBIGUOUS_CONVERSATION))
        val store = FakeStore()

        coordinator(adapter, store).capture("com.tencent.mm", snapshot)

        assertTrue(store.pending.isEmpty())
        assertTrue(store.seen.isEmpty())
    }

    @Test
    fun parserExceptionIsSwallowedAndCounted() = runBlocking {
        val adapter = object : ChatAppAdapter {
            override val packageName = "com.tencent.mm"
            override fun parse(root: UiNodeSnapshot): ParseResult = error("broken fixture")
        }
        val coordinator = coordinator(adapter, FakeStore())

        coordinator.capture("com.tencent.mm", snapshot)

        assertEquals(1, coordinator.internalFailureCount.get())
    }

    @Test
    fun sameCapturedResourceIsStoredOnceAndReferencedByMultipleMessages() = runBlocking {
        val bounds = IntRect(10, 10, 70, 70)
        val adapter = FakeAdapter(success(
            mediaMessage(bounds, ordinal = 0),
            mediaMessage(bounds, ordinal = 1),
        ))
        val store = FakeStore()
        val asset = pendingAsset("asset-sha")
        var captureCalls = 0
        val mediaCapturer = MediaAssetCapturer { windowId, _, requests ->
            assertEquals(7, windowId)
            captureCalls += 1
            requests.associate { it.messageIndex to asset }
        }

        coordinator(adapter, store, mediaCapturer = mediaCapturer)
            .capture("com.tencent.mm", snapshot, windowId = 7)

        assertEquals(1, captureCalls)
        assertEquals(setOf("asset-sha"), store.assets.keys)
        assertEquals(2, store.pending.size)
        assertTrue(store.pending.all { it.requiredAssetHashesJson.contains("asset-sha") })
    }

    @Test
    fun failedMediaCaptureKeepsMetadataMessageWithoutAssetReference() = runBlocking {
        val adapter = FakeAdapter(success(mediaMessage(IntRect(10, 10, 70, 70))))
        val store = FakeStore()

        coordinator(adapter, store, mediaCapturer = MediaAssetCapturer { _, _, _ -> emptyMap() })
            .capture("com.tencent.mm", snapshot, windowId = 7)

        assertEquals(1, store.pending.size)
        assertEquals("[]", store.pending.single().requiredAssetHashesJson)
        assertTrue(store.pending.single().payloadJson.contains("asset_capture_failed"))
    }

    @Test
    fun conversationScreenshotRequestsLossyWebpEncoding() = runBlocking {
        val screenshot = mediaMessage(IntRect(0, 10, 100, 90)).copy(
            metadata = mapOf("capture_kind" to "conversation_screenshot"),
        )
        var lossyWebp = false
        val capturer = MediaAssetCapturer { _, _, requests ->
            lossyWebp = requests.single().lossyWebp
            mapOf(0 to pendingAsset("asset-sha"))
        }

        coordinator(FakeAdapter(success(screenshot)), FakeStore(), mediaCapturer = capturer)
            .capture("com.tencent.mm", snapshot, windowId = 7)

        assertTrue(lossyWebp)
    }

    @Test
    fun conversationScreenshotOnlyPersistsWhenCapturedImageHashChanges() = runBlocking {
        val screenshot = mediaMessage(IntRect(0, 10, 100, 90)).copy(
            metadata = mapOf("capture_kind" to "conversation_screenshot"),
        )
        val adapter = FakeAdapter(success(screenshot))
        val store = FakeStore()
        var currentHash = "first-screen"
        val coordinator = coordinator(
            adapter,
            store,
            mediaCapturer = MediaAssetCapturer { _, _, _ -> mapOf(0 to pendingAsset(currentHash)) },
        )

        coordinator.capture("com.tencent.mm", snapshot, windowId = 7)
        coordinator.capture("com.tencent.mm", snapshot, windowId = 7)
        currentHash = "second-screen"
        coordinator.capture("com.tencent.mm", snapshot, windowId = 7)

        assertEquals(2, store.pending.size)
        assertEquals(setOf("first-screen", "second-screen"), store.assets.keys)
    }

    @Test
    fun repeatedNotificationAndMatchingPageMessageShareStableFingerprint() = runBlocking {
        val notificationMessage = message("同一条消息", time = "")
            .copy(displayedTime = null, metadata = mapOf("capture_source" to "notification"))
        val adapter = FakeAdapter(success(notificationMessage.copy(metadata = emptyMap())))
        val store = FakeStore()
        val coordinator = coordinator(adapter, store)

        coordinator.captureParsed(conversation, listOf(notificationMessage))
        coordinator.captureParsed(conversation, listOf(notificationMessage))
        coordinator.capture("com.tencent.mm", snapshot)

        assertEquals(1, store.pending.size)
        assertEquals(1, store.seen.size)
    }

    @Test
    fun captureParsedReportsWhetherOutboxAcceptedTheMessage() = runBlocking {
        val store = FakeStore()
        val coordinator = coordinator(FakeAdapter(success()), store)
        val captured = message("新消息", "18:30")

        assertEquals(CapturePersistResult.INSERTED, coordinator.captureParsed(conversation, listOf(captured)))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, coordinator.captureParsed(conversation, listOf(captured)))
    }

    @Test fun unresolvedScreenshotIsRetainedWithoutRelaxingOtherIdentityChecks() = runBlocking {
        val store = FakeStore()
        val coordinator = coordinator(FakeAdapter(success()), store)
        val pending = conversation.copy(
            accountKey = "wechat-empty-tree",
            externalKey = "screenshot-v2:" + "a".repeat(64),
            displayName = "待确认会话 aaaaaaaa",
            identityConfidence = 0.55,
        )
        val screenshot = CapturedMessage(
            conversationKey = null,
            senderKey = "viewport",
            direction = ChatDirection.SYSTEM,
            messageType = ChatMessageType.IMAGE,
            metadata = mapOf(
                "capture_source" to "wechat_empty_tree_screenshot",
                "conversation_identity_status" to "pending",
            ),
        )
        assertEquals(CapturePersistResult.INSERTED, coordinator.captureParsed(
            pending, listOf(screenshot), mapOf(0 to pendingAsset("pending-image")),
        ))
        assertEquals(1, store.assets.size)
        assertTrue(store.pending.single().payloadJson.contains("待确认会话"))
        assertEquals(CapturePersistResult.FAILED, coordinator.captureParsed(pending, listOf(message("低置信度文字", "18:31"))))
        assertEquals(CapturePersistResult.FAILED, coordinator.captureParsed(pending.copy(externalKey = "peer"), listOf(screenshot), mapOf(0 to pendingAsset("other"))))
        assertEquals(CapturePersistResult.FAILED, coordinator.captureParsed(pending, listOf(screenshot)))
    }

    @Test fun allPlatformsKeepFirstPendingScreenshotWithoutWaitingForName() = runBlocking {
        for (platform in ChatPlatform.entries) {
            val store = FakeStore()
            val coordinator = coordinator(FakeAdapter(success()), store)
            val pending = conversation.copy(
                platform = platform, accountKey = "${platform.wireName}-local",
                externalKey = "capture-v3:" + "a".repeat(64),
                displayName = "待确认会话 aaaaaaaa", identityConfidence = 0.55,
            )
            val screenshot = CapturedMessage(
                conversationKey = null, senderKey = "viewport", direction = ChatDirection.SYSTEM,
                messageType = ChatMessageType.IMAGE,
                metadata = mapOf("capture_source" to "${platform.wireName}_screenshot", "capture_kind" to "conversation_screenshot", "conversation_identity_status" to "pending"),
            )
            assertEquals(platform.wireName, CapturePersistResult.INSERTED,
                coordinator.captureParsed(pending, listOf(screenshot), mapOf(0 to pendingAsset("first-image"))))
            assertEquals(1, store.pending.size)
            store.assets.clear() // 模拟首张已上传；本地原图可能已清理。
            assertEquals(CapturePersistResult.INSERTED, coordinator.captureParsed(
                pending.copy(displayName = "真实名字", identityConfidence = 0.85),
                listOf(screenshot.copy(metadata = screenshot.metadata + ("conversation_identity_status" to "confirmed"))),
                mapOf(0 to pendingAsset("first-image")),
            ))
            assertEquals("确认名称应有持久化补传，不能因截图已见而丢失", 2, store.pending.size)
            assertEquals("确认补传不应重新等待可能已清理的原图", 0, store.assets.size)
            assertEquals(CapturePersistResult.ALREADY_PERSISTED, coordinator.captureParsed(
                pending.copy(displayName = "真实名字", identityConfidence = 0.85),
                listOf(screenshot.copy(metadata = screenshot.metadata + ("conversation_identity_status" to "confirmed"))),
                mapOf(0 to pendingAsset("first-image")),
            ))
            assertEquals(2, store.pending.size)
        }
    }

    @Test fun screenshotPipelineUsesSameFrameTitleEvidenceAndDoesNotUploadTitleCrop() = runBlocking {
        for (platform in ChatPlatform.entries) {
            val store = FakeStore()
            var now = 1000L
            val shot = mediaMessage(IntRect(0, 20, 100, 80)).copy(direction = ChatDirection.SYSTEM,
                metadata = mapOf("capture_source" to "${platform.wireName}_screenshot", "capture_kind" to "conversation_screenshot"))
            val adapter = FakeAdapter(ParseResult.Success(ParsedViewport(
                conversation.copy(platform = platform, accountKey = "${platform.wireName}-local", displayName = "名字"),
                listOf(shot), titleBounds = IntRect(0, 0, 100, 20),
            )))
            val coordinator = CaptureCoordinator(adapterForPackage = { adapter }, store = store,
                deviceId = { "device" }, wakeUploader = {}, clock = { now }, titleSignature = { it.sha256 },
                mediaCapturer = MediaAssetCapturer { _, _, requests ->
                    assertEquals(setOf(0, -1), requests.map { it.messageIndex }.toSet())
                    mapOf(0 to pendingAsset("body"), -1 to pendingAsset("a".repeat(64)))
                })
            assertTrue(coordinator.capture(adapter.packageName, snapshot, 1))
            assertEquals(1, store.pending.size)
            assertTrue(store.pending.first().payloadJson.contains("待确认会话"))
            assertEquals(setOf("body"), store.assets.keys)
            now += 800
            org.junit.Assert.assertFalse(coordinator.capture(adapter.packageName, snapshot, 1))
            assertEquals(2, store.pending.size)
            assertTrue(store.pending.last().payloadJson.contains("confirmed"))
            assertEquals(store.pending.first().fingerprint, store.pending.last().fingerprint)
        }
    }

    @Test fun navigationDuringScreenshotRetainsImageButNeverConfirmsStaleName() = runBlocking {
        val store = FakeStore()
        val adapter = FakeAdapter(ParseResult.Success(ParsedViewport(
            conversation.copy(platform = ChatPlatform.QQ, accountKey = "qq-local"),
            listOf(mediaMessage(IntRect(0, 20, 100, 80)).copy(direction = ChatDirection.SYSTEM,
                metadata = mapOf("capture_source" to "qq_screenshot", "capture_kind" to "conversation_screenshot"))),
            titleBounds = IntRect(0, 0, 100, 20),
        )))
        lateinit var coordinator: CaptureCoordinator
        coordinator = CaptureCoordinator(adapterForPackage = { adapter }, store = store, deviceId = { "device" },
            wakeUploader = {}, titleSignature = { it.sha256 }, mediaCapturer = MediaAssetCapturer { _, _, _ ->
                coordinator.resetConversationIdentity()
                mapOf(0 to pendingAsset("body"), -1 to pendingAsset("a".repeat(64)))
            })
        coordinator.capture(adapter.packageName, snapshot, 1)
        assertEquals(1, store.pending.size)
        assertTrue(store.pending.single().payloadJson.contains("capture-pending:"))
        assertTrue(store.pending.single().payloadJson.contains("待确认会话"))
    }

    @Test fun confirmationDoesNotChangeImageIdentityWhenGroupTypeBecomesKnown() {
        val pending = conversation.copy(externalKey = "capture-v3:" + "a".repeat(64), conversationType = ConversationType.UNKNOWN)
        val confirmed = pending.copy(conversationType = ConversationType.GROUP)
        assertEquals(pending.stableKeyOrNull(), confirmed.stableKeyOrNull())
    }

    @Test fun pendingNotificationIsRetainedButCannotMasqueradeAsScreenshotOrOtherAccount() = runBlocking {
        val store = FakeStore()
        val coordinator = coordinator(FakeAdapter(success()), store)
        val parsed = com.yuyan.imemodule.data.capture.notification.NotificationParser().parse(
            com.yuyan.imemodule.data.capture.notification.NotificationSnapshot("com.tencent.mobileqq", "thread", "测试好友", "收到", 1000),
        )!!
        assertEquals(CapturePersistResult.INSERTED, coordinator.captureParsed(parsed.conversation, listOf(parsed.message)))
        assertEquals(CapturePersistResult.FAILED, coordinator.captureParsed(parsed.conversation.copy(accountKey = "qq-local"), listOf(parsed.message)))
        assertEquals(CapturePersistResult.FAILED, coordinator.captureParsed(parsed.conversation, listOf(parsed.message.copy(metadata = mapOf("capture_source" to "notification")))))
    }

    @Test fun unknownNotificationFallbackIsKeptAsPendingNotAsNamedConversation() = runBlocking {
        val store = FakeStore()
        val coordinator = coordinator(FakeAdapter(success()), store)
        val pending = conversation.copy(accountKey = "notification-screenshot",
            externalKey = "notification-fallback-v2:" + "a".repeat(64), identityConfidence = 0.55, displayName = "待确认截图")
        val shot = mediaMessage(IntRect(0, 0, 100, 80)).copy(mediaBounds = null, metadata = mapOf(
            "capture_source" to "notification_screenshot_fallback", "source_package" to "com.tencent.mm",
            "notification_key" to "thread", "conversation_identity_status" to "pending",
        ))
        assertEquals(CapturePersistResult.INSERTED, coordinator.captureParsed(pending, listOf(shot), mapOf(0 to pendingAsset("fallback"))))
        assertEquals(CapturePersistResult.FAILED, coordinator.captureParsed(pending, listOf(shot.copy(metadata = shot.metadata + ("source_package" to "com.example.other"))), mapOf(0 to pendingAsset("bad"))))
    }

    @Test fun unchangedUnresolvedScreenshotDoesNotCreateRepeatedPendingConversations() = runBlocking {
        val store = FakeStore()
        val adapter = FakeAdapter(ParseResult.Success(ParsedViewport(
            conversation.copy(accountKey = "wechat-local", displayName = "很长的群名…"),
            listOf(mediaMessage(IntRect(0, 20, 100, 80)).copy(direction = ChatDirection.SYSTEM,
                metadata = mapOf("capture_source" to "wechat_screenshot", "capture_kind" to "conversation_screenshot"))),
            titleBounds = IntRect(0, 0, 100, 20),
        )))
        val coordinator = CaptureCoordinator(adapterForPackage = { adapter }, store = store, deviceId = { "device" },
            wakeUploader = {}, titleSignature = { null }, mediaCapturer = MediaAssetCapturer { _, _, _ ->
                mapOf(0 to pendingAsset("body"), -1 to pendingAsset("a".repeat(64)))
            })
        repeat(3) { assertTrue(coordinator.capture(adapter.packageName, snapshot, 1)) }
        assertEquals(1, store.pending.size)
        coordinator.resetConversationIdentity()
        coordinator.capture(adapter.packageName, snapshot, 1)
        assertEquals("导航后未知对象不凭相同图片串联", 2, store.pending.size)
    }

    @Test fun legacyScreenshotFingerprintFormatDoesNotChangeOnUpgrade() {
        val old = conversation.copy(externalKey = "screenshot-v2:" + "a".repeat(64), conversationType = ConversationType.GROUP)
        assertEquals("wechat|account|group|screenshot-v2:" + "a".repeat(64), old.stableKeyOrNull())
    }

    @Test fun recoveredScreenshotReplaysOriginalPendingFingerprint() = runBlocking {
        val store = FakeStore()
        val worker = coordinator(FakeAdapter(success()), store)
        val key = "screenshot-v2:pending:00000000-0000-4000-8000-000000000001"
        val old = conversation.copy(accountKey="wechat-empty-tree", externalKey=key, conversationType=ConversationType.UNKNOWN, identityConfidence=0.0)
        val shot = CapturedMessage(conversationKey=null, senderKey="$key:viewport", direction=ChatDirection.SYSTEM, messageType=ChatMessageType.IMAGE,
            metadata=mapOf("capture_source" to "wechat_empty_tree_screenshot", "conversation_identity_status" to "pending"))
        assertEquals(CapturePersistResult.INSERTED,worker.captureParsed(old,listOf(shot),mapOf(0 to pendingAsset("image"))))
        val known = old.copy(externalKey="screenshot-v2:"+"a".repeat(64),displayName="已知",identityConfidence=0.85)
        worker.captureParsed(known,listOf(shot.copy(senderKey="${known.externalKey}:viewport",metadata=shot.metadata+mapOf("conversation_identity_status" to "confirmed","conversation_identity_previous_key" to key))),mapOf(0 to pendingAsset("image")))
        assertEquals(2,store.pending.size)
        assertEquals(store.pending.first().fingerprint,store.pending.last().fingerprint)
    }

    @Test fun fixedPageWithoutTitleCropAlsoRetriesMissingImageInsteadOfQueuingEmptyRecord() = runBlocking {
        val store=FakeStore()
        val shot=mediaMessage(IntRect(0,0,100,100)).copy(direction=ChatDirection.SYSTEM,metadata=mapOf("capture_source" to "wechat_page_screenshot","capture_kind" to "conversation_screenshot"))
        val adapter=FakeAdapter(ParseResult.Success(ParsedViewport(conversation.copy(accountKey="wechat-empty-tree",displayName="朋友圈"),listOf(shot))))
        val worker=coordinator(adapter,store,mediaCapturer=MediaAssetCapturer { _,_,_->emptyMap() })
        assertTrue(worker.capture(adapter.packageName,snapshot,1));assertTrue(store.pending.isEmpty())
    }

    @Test fun screenshotFailureRequestsRetryWithoutQueuingEmptyPlaceholder() = runBlocking {
        val store=FakeStore()
        val shot=mediaMessage(IntRect(0,20,100,80)).copy(direction=ChatDirection.SYSTEM,metadata=mapOf("capture_source" to "qq_screenshot","capture_kind" to "conversation_screenshot"))
        val adapter=FakeAdapter(ParseResult.Success(ParsedViewport(conversation.copy(platform=ChatPlatform.QQ,accountKey="qq-local"),listOf(shot),titleBounds=IntRect(0,0,100,20))))
        val worker=coordinator(adapter,store,mediaCapturer=MediaAssetCapturer { _,_,_->emptyMap() })
        assertTrue(worker.capture(adapter.packageName,snapshot,1));assertTrue(store.pending.isEmpty())
    }

    private fun coordinator(
        adapter: ChatAppAdapter,
        store: FakeStore,
        mediaCapturer: MediaAssetCapturer? = null,
        wake: () -> Unit = {},
    ) = CaptureCoordinator(
        adapterForPackage = { adapter },
        store = store,
        deviceId = { "00000000-0000-4000-8000-000000000001" },
        clock = { 1_700_000_000_000L },
        wakeUploader = wake,
        mediaCapturer = mediaCapturer,
    )

    private fun success(vararg messages: CapturedMessage) = ParseResult.Success(
        ParsedViewport(conversation, messages.toList()),
    )

    private fun message(text: String, time: String, ordinal: Int = 0) = CapturedMessage(
        conversationKey = null,
        senderKey = "peer",
        senderName = "对方",
        direction = ChatDirection.INCOMING,
        messageType = ChatMessageType.TEXT,
        text = text,
        displayedTime = time,
        sameContentOrdinal = ordinal,
    )

    private fun mediaMessage(bounds: IntRect, ordinal: Int = 0) = CapturedMessage(
        conversationKey = null,
        senderKey = "peer",
        direction = ChatDirection.INCOMING,
        messageType = ChatMessageType.IMAGE,
        sameContentOrdinal = ordinal,
        mediaBounds = bounds,
    )

    private fun pendingAsset(hash: String) = PendingAssetEntity(
        sha256 = hash,
        localPath = "/tmp/$hash",
        mimeType = "image/png",
        perceptualHash = null,
        width = 60,
        height = 60,
    )

    private class FakeAdapter(var result: ParseResult) : ChatAppAdapter {
        override val packageName = "com.tencent.mm"
        override fun parse(root: UiNodeSnapshot): ParseResult = result
    }

    private class FakeStore : CaptureOutboxStore {
        val seen = linkedSetOf<String>()
        val pending = mutableListOf<PendingMessageEntity>()
        val assets = linkedMapOf<String, PendingAssetEntity>()

        override suspend fun enqueueIfNew(
            seenMessage: SeenMessageEntity,
            pendingMessage: PendingMessageEntity,
            pendingAssets: List<PendingAssetEntity>,
        ): Boolean {
            if (!seen.add(seenMessage.fingerprint)) return false
            pendingAssets.forEach { assets.putIfAbsent(it.sha256, it) }
            pending += pendingMessage
            return true
        }
    }
    @Test fun truncatedScreenshotIsPersistedWithoutRelaxingOtherLowConfidenceInputs() = runBlocking {
        val store=FakeStore();val coordinator=coordinator(FakeAdapter(success()),store)
        val partial=conversation.copy(accountKey="wechat-empty-tree",
            externalKey="screenshot-v2:truncated:11111111-1111-1111-1111-111111111111",
            displayName="测试…店（名称被截断）",identityConfidence=.55)
        val image=CapturedMessage(conversationKey=null,senderKey="viewport",direction=ChatDirection.SYSTEM,
            messageType=ChatMessageType.IMAGE,metadata=mapOf("capture_source" to "wechat_empty_tree_screenshot",
                "conversation_identity_status" to "truncated","conversation_identity_source" to "on_device_title_ocr"))
        val assets=mapOf(0 to pendingAsset("truncated-image"))
        assertEquals(CapturePersistResult.INSERTED,coordinator.captureParsed(partial,listOf(image),assets))
        assertTrue(store.pending.single().payloadJson.contains("名称被截断"))
        assertEquals(CapturePersistResult.FAILED,coordinator.captureParsed(partial,listOf(image)))
        assertEquals(CapturePersistResult.FAILED,coordinator.captureParsed(partial,listOf(image.copy(text="不允许正文")),assets))
        assertEquals(CapturePersistResult.FAILED,coordinator.captureParsed(partial.copy(platform=ChatPlatform.QQ),listOf(image),assets))
        assertEquals(CapturePersistResult.FAILED,coordinator.captureParsed(partial.copy(externalKey="peer"),listOf(image),assets))
        assertEquals(CapturePersistResult.FAILED,coordinator.captureParsed(partial,listOf(image.copy(metadata=emptyMap())),assets))
    }
}
