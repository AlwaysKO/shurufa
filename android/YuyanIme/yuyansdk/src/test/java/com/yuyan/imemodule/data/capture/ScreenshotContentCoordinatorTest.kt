package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.media.ScreenshotContentBlocks
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.model.stableKeyOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.yuyan.imemodule.data.capture.media.ChatCaptureAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [30])
class ScreenshotContentCoordinatorTest {
    @org.junit.Before @org.junit.After fun resetRuntime() {
        com.yuyan.imemodule.data.collect.resetImageInputForTest()
        com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest()
    }

    private val conversation = CapturedConversation(
        platform = ChatPlatform.WECHAT,
        accountKey = "wechat-empty-tree",
        externalKey = "screenshot-v2:" + "a".repeat(64),
        displayName = "测试联系人",
        conversationType = ConversationType.DIRECT,
        identityConfidence = 0.95,
    )
    private val screenshot = CapturedMessage(
        conversationKey = null,
        senderKey = "viewport",
        direction = ChatDirection.SYSTEM,
        messageType = ChatMessageType.IMAGE,
        metadata = mapOf(
            "capture_source" to "wechat_empty_tree_screenshot",
            "capture_kind" to "conversation_screenshot",
            "conversation_identity_status" to "confirmed",
        ),
    )

    @Test fun returningToAnEarlierBodyUsesSavedContentDespiteDifferentAssetHashes() = runBlocking {
        val store = FakeStore()
        var wakes = 0
        val worker = worker(store) { wakes++ }
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "first", evidence("a", "b")))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "second", evidence("c", "d")))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED,
            persist(worker, "third", evidence("a", "b", height = 450)))
        assertEquals(listOf("first", "second"), store.assets.map { it.sha256 })
        assertEquals(2, store.pending.size)
        assertEquals(2, store.attempts)
        assertEquals(2, wakes)
    }

    @Test fun explicitSendFrameDoesNotReuseAnEarlierMessageInstanceByBodyContent() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val content = evidence("context", "same-text")
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "before-send", content))
        val attempt = ChatCaptureAttempt({ true }, { true }, { true }, allowsSettledSendFrame = true)
        assertTrue(attempt.acceptFrame())
        val result = withContext(attempt) { persist(worker, "after-send", content) }
        assertEquals(CapturePersistResult.INSERTED, result)
        assertEquals(listOf("before-send", "after-send"), store.assets.map { it.sha256 })
    }

    @Test fun sendInvalidatesOnlyItsConversationHistoryBeforeTheNextSettledFrame() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val other = conversation.copy(externalKey = "screenshot-v2:" + "b".repeat(64))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "old-a", evidence("a", "b")))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "old-c", evidence("c", "d")))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "other", evidence("a", "b", peer = other), peer = other))
        val attempt = ChatCaptureAttempt({ true }, { true }, { true }, allowsSettledSendFrame = true)
        assertTrue(attempt.acceptFrame())
        withContext(attempt) {
            assertEquals(CapturePersistResult.INSERTED, persist(worker, "sent-a", evidence("a", "b")))
        }
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "new-settled-c", evidence("c", "d")))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED,
            persist(worker, "other-revisit", evidence("a", "b", peer = other), peer = other))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, persist(worker, "sent-a-revisit", evidence("a", "b")))
    }

    @Test fun identicalPixelsFromDistinctExplicitSendsKeepSeparateAssetsButSameAttemptRetryDoesNot() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val pixels = "a".repeat(64)
        persistPixels(worker, "same-image", pixels)
        repeat(2) { index ->
            val attempt = ChatCaptureAttempt({ true }, { true }, { true }, allowsSettledSendFrame = true)
            assertTrue(attempt.acceptFrame())
            withContext(attempt) {
                persistPixels(worker, "same-image", pixels)
                assertEquals(index + 2, store.assets.size)
                persistPixels(worker, "same-image", pixels)
                assertEquals("同次发送的持久化重试不能生成另一个图片实例", index + 2, store.assets.size)
            }
        }
    }

    @Test fun frameAlreadyEnqueuingBeforeSendCannotRepopulatePreSendContentHistory() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val attempt = ChatCaptureAttempt({ true }, { true }, { true }, allowsSettledSendFrame = true)
        assertTrue(attempt.acceptFrame())
        store.afterNextAcceptedEnqueue = {
            runBlocking {
                withContext(attempt) {
                    assertEquals(CapturePersistResult.INSERTED, persist(worker, "sent", evidence("new", "send")))
                }
            }
        }
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "old-in-flight", evidence("old", "body")))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "new-instance-of-old-body", evidence("old", "body")))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, persist(worker, "sent-revisit", evidence("new", "send")))
    }

    @Test fun persistedContentDecisionUsesOnlyFixedReasonAndLeavesFingerprintUnchanged() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "first", evidence("a", "b")))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "second", evidence("a", "new")))
        val reasons = store.pending.map {
            org.json.JSONObject(it.payloadJson).getJSONObject("message").getJSONObject("metadata")
                .optString("screenshot_content_reason")
        }
        assertEquals(listOf("no_saved_content", "content_changed"), reasons)
        assertTrue(reasons.all { reason -> ScreenshotContentReason.entries.any { it.wireName == reason } })
        assertEquals(messageFingerprint(screenshot.copy(conversationKey = conversation.stableKeyOrNull(),
            assetSha256 = listOf("first"))), store.pending.first().fingerprint)
        assertEquals(listOf("first", "second"), store.assets.map { it.sha256 })
    }

    @Test fun failedEnqueueDoesNotConsumeContentAndRetryCanPersistIt() = runBlocking {
        val store = FakeStore().apply { throwOnEnqueue = true }
        val worker = worker(store)
        val content = evidence("a", "b")
        assertEquals(CapturePersistResult.FAILED, persist(worker, "failed", content))
        assertTrue(store.pending.isEmpty())
        store.throwOnEnqueue = false
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "retry", content))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, persist(worker, "after-retry", content))
        assertEquals(listOf("retry"), store.assets.map { it.sha256 })
        assertEquals(2, store.attempts)
    }

    @Test fun rejectedEnqueueAndRejectedConfirmationDoNotPopulateContentCache() = runBlocking {
        val store = FakeStore().apply { rejectEnqueue = true }
        val worker = worker(store)
        val content = evidence("a", "b")
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, persist(worker, "rejected", content))
        assertEquals(2, store.attempts) // 图片及身份确认均未进入 outbox。
        store.rejectEnqueue = false
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "accepted", content))
        assertEquals(listOf("accepted"), store.assets.map { it.sha256 })
    }

    @Test fun identityResetClearsBodyHistory() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "before", evidence("a", "b")))
        worker.resetConversationIdentity()
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "after", evidence("a", "b")))
        assertEquals(2, store.pending.size)
    }

    @Test fun identityResetDuringEnqueuePreventsOldFrameFromRepopulatingHistory() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        store.afterNextAcceptedEnqueue = { worker.resetConversationIdentity() }
        val content = evidence("a", "b")
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "old-scope", content))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "new-scope", content))
        assertEquals(listOf("old-scope", "new-scope"), store.assets.map { it.sha256 })
        assertEquals(2, store.pending.size)
    }

    @Test fun anotherConversationAccountTitleOrWidthKeepsItsOwnScreenshot() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val other = conversation.copy(externalKey = "screenshot-v2:" + "b".repeat(64))
        val otherAccount = conversation.copy(accountKey = "other-account")
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "first", evidence("a", "b")))
        assertEquals(CapturePersistResult.INSERTED,
            persist(worker, "other-peer", evidence("a", "b", peer = other), peer = other))
        assertEquals(CapturePersistResult.INSERTED,
            persist(worker, "other-account", evidence("a", "b", peer = otherAccount), peer = otherAccount))
        assertEquals(CapturePersistResult.INSERTED,
            persist(worker, "other-title", evidence("a", "b", title = "new-title-pixels")))
        assertEquals(CapturePersistResult.INSERTED,
            persist(worker, "other-width", evidence("a", "b", width = 201)))
        assertEquals(5, store.pending.size)
    }

    @Test fun newShortBlockAndRepeatedBlockCountRemainPersistable() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val frames = listOf(
            evidence("a", "b"),
            evidence("a", "b", "one-character"),
            evidence("a", "b", "b"),
            evidence("a", "b", "b", "b"),
            evidence("a", "b", "b", "b"),
        )
        frames.forEachIndexed { index, content ->
            assertEquals("第 $index 帧不能按旧块集合丢弃", CapturePersistResult.INSERTED,
                persist(worker, "frame-$index", content))
        }
        assertEquals(5, store.pending.size)
    }

    @Test fun incompleteEvidenceCannotTurnNewAssetIntoAnOldScreenshot() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "known", evidence("a", "b")))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "missing-evidence", null))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "single-block", evidence("a")))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "single-block-again", evidence("a")))
        assertEquals(4, store.pending.size)
    }

    @Test fun aNewClippedEdgeIsKeptEvenWhenCompleteBodyBlocksWereSeen() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        fun clipped(bottom: String): ScreenshotContentEvidence {
            val rows = listOf("old-top", "blank", "a", "blank", "b", "blank", bottom)
            val hashes = rows.flatMap {
                java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList()
            }.toByteArray()
            return evidence("a", "b").copy(blocks = ScreenshotContentBlocks(
                200, rows.size, listOf("a", "b"), hashes, hasClippedEdges = true,
            ))
        }
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "old-bottom", clipped("old-bottom")))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "new-bottom", clipped("new-character")))
        assertEquals(2, store.pending.size)
    }

    @Test fun staleCaptureDoesNotSeedContentHistory() = runBlocking {
        val store = FakeStore()
        val worker = CaptureCoordinator(store = store, deviceId = { "device" }, wakeUploader = {},
            captureGeneration = { 2L })
        val content = evidence("a", "b")
        assertEquals(CapturePersistResult.FAILED, worker.captureParsed(conversation,
            listOf(screenshot), mapOf(0 to asset("stale")), captureToken = 1L,
            screenshotContentByMessage = mapOf(0 to content)))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "current", content))
        assertEquals(listOf("current"), store.assets.map { it.sha256 })
    }

    @Test fun pendingIdentityCanConsultConfirmedEvidenceButCannotSeedItsHistory() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val pendingPeer = conversation.copy(
            externalKey = "screenshot-v2:pending:00000000-0000-4000-8000-000000000001",
            displayName = "待确认会话", identityConfidence = 0.55,
        )
        val pendingMessage = screenshot.copy(metadata = screenshot.metadata +
            ("conversation_identity_status" to "pending"))
        val confirmedEvidence = evidence("a", "b")
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "pending-one", confirmedEvidence,
            peer = pendingPeer, message = pendingMessage))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "pending-two", confirmedEvidence,
            peer = pendingPeer, message = pendingMessage))
        assertEquals(CapturePersistResult.INSERTED,
            persist(worker, "confirmed", confirmedEvidence))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, persist(worker, "pending-after-confirmed", confirmedEvidence,
            peer = pendingPeer, message = pendingMessage))
        assertEquals(listOf("pending-one", "pending-two", "confirmed"), store.assets.map { it.sha256 })
    }

    @Test fun verifiedListFingerprintRetainsPrecedenceOverChatBodyEvidence() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val page = conversation.copy(externalKey = "screenshot-v2:wechat-page:test", displayName = "微信",
            conversationType = ConversationType.UNKNOWN)
        fun listMessage(hash: String) = screenshot.copy(metadata = screenshot.metadata + mapOf(
            "capture_source" to "wechat_page_screenshot",
            "conversation_identity_source" to "wechat_page_title",
            "wechat_list_content_sha256" to hash,
        ))
        val content = evidence("same-body-a", "same-body-b", peer = page)
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "list-one", content,
            peer = page, message = listMessage("a".repeat(64))))
        assertEquals(CapturePersistResult.INSERTED, persist(worker, "changed-list", content,
            peer = page, message = listMessage("b".repeat(64))))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, persist(worker, "red-dot-only", content,
            peer = page, message = listMessage("a".repeat(64))))
        assertEquals(listOf("list-one", "changed-list"), store.assets.map { it.sha256 })
        assertEquals(2, store.pending.size)
    }

    @Test fun differentRawPixelsSurviveEvenWhenLossyEncodingProducesTheSameAsset() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        assertEquals(CapturePersistResult.INSERTED,
            persistPixels(worker, "same-encoded-image", "a".repeat(64)))
        assertEquals(CapturePersistResult.INSERTED,
            persistPixels(worker, "same-encoded-image", "b".repeat(64)))
        assertEquals("有损编码不能吞掉原始像素中的细字变化", 2,
            store.pending.map { it.fingerprint }.distinct().size)
        assertEquals(2, store.assets.size)
        assertTrue(store.pending.all { it.requiredAssetHashesJson.contains("same-encoded-image") })
    }

    @Test fun differentEncodingsWithoutConfirmedBlockEvidenceDoNotCreateMissingAssetReferences() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val pixels = "a".repeat(64)
        assertEquals(CapturePersistResult.INSERTED, persistPixels(worker, "first-encoding", pixels))
        assertEquals(CapturePersistResult.INSERTED,
            persistPixels(worker, "second-encoding", pixels))
        // 缺少可确认的块证据时保守保存；不能重放指向未入队新编码的旧指纹。
        assertEquals(2, store.pending.size)
        assertEquals(listOf("first-encoding", "second-encoding"), store.assets.map { it.sha256 })
        assertTrue(store.pending.first().requiredAssetHashesJson.contains("first-encoding"))
        assertTrue(store.pending.last().requiredAssetHashesJson.contains("second-encoding"))
    }

    @Test fun rawPixelFingerprintStillAllowsConfirmationWithoutReinsertingTheImage() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        val pendingKey = "screenshot-v2:pending:00000000-0000-4000-8000-000000000001"
        val pendingPeer = conversation.copy(externalKey = pendingKey, displayName = "待确认会话",
            identityConfidence = 0.55)
        val pendingMessage = screenshot.copy(metadata = screenshot.metadata +
            ("conversation_identity_status" to "pending"))
        val confirmation = screenshot.copy(metadata = screenshot.metadata +
            ("conversation_identity_previous_key" to pendingKey))
        val pixels = "a".repeat(64)
        assertEquals(CapturePersistResult.INSERTED, persistPixels(worker, "original", pixels,
            peer = pendingPeer, message = pendingMessage))
        assertEquals(CapturePersistResult.INSERTED, persistPixels(worker, "original", pixels,
            message = confirmation))
        assertEquals(CapturePersistResult.ALREADY_PERSISTED, persistPixels(worker, "original", pixels,
            message = confirmation))
        assertEquals(2, store.pending.size)
        assertEquals(store.pending.first().fingerprint, store.pending.last().fingerprint)
        assertEquals(listOf("original"), store.assets.map { it.sha256 })
        assertTrue(store.pending.last().payloadJson.contains("confirmed"))
    }

    @Test fun invalidPixelHashFallsBackToIndependentAssetFingerprints() = runBlocking {
        val store = FakeStore()
        val worker = worker(store)
        assertEquals(CapturePersistResult.INSERTED, persistPixels(worker, "first", "not-a-sha256"))
        assertEquals(CapturePersistResult.INSERTED, persistPixels(worker, "second", "not-a-sha256"))
        assertEquals(2, store.pending.map { it.fingerprint }.distinct().size)
        assertEquals(listOf("first", "second"), store.assets.map { it.sha256 })
    }

    private fun evidence(
        vararg hashes: String,
        peer: CapturedConversation = conversation,
        title: String = "same-title-pixels",
        width: Int = 200,
        height: Int = 500,
    ) = ScreenshotContentEvidence(requireNotNull(peer.stableKeyOrNull()), title,
        ScreenshotContentBlocks(width, height, hashes.toList()))

    private fun worker(store: FakeStore, wake: () -> Unit = {}) = CaptureCoordinator(
        store = store, deviceId = { "device" }, clock = { 1_700_000_000_000L }, wakeUploader = wake,
    )

    private suspend fun persist(
        worker: CaptureCoordinator,
        assetHash: String,
        content: ScreenshotContentEvidence?,
        peer: CapturedConversation = conversation,
        message: CapturedMessage = screenshot,
    ) = worker.captureParsed(peer, listOf(message), mapOf(0 to asset(assetHash)),
        screenshotContentByMessage = content?.let { mapOf(0 to it) }.orEmpty())

    private suspend fun persistPixels(
        worker: CaptureCoordinator,
        assetHash: String,
        pixels: String,
        peer: CapturedConversation = conversation,
        message: CapturedMessage = screenshot,
    ) = worker.captureParsed(peer, listOf(message), mapOf(0 to asset(assetHash)),
        screenshotPixelHashes = mapOf(0 to pixels))

    private fun asset(hash: String) = PendingAssetEntity(
        sha256 = hash, localPath = "/tmp/$hash", mimeType = "image/webp",
        perceptualHash = null, width = 200, height = 500,
    )

    private class FakeStore : CaptureOutboxStore {
        var throwOnEnqueue = false
        var rejectEnqueue = false
        var attempts = 0
        var afterNextAcceptedEnqueue: (() -> Unit)? = null
        val pending = mutableListOf<PendingMessageEntity>()
        val assets = mutableListOf<PendingAssetEntity>()
        private val seen = mutableSetOf<String>()

        override suspend fun enqueueIfNew(
            seenMessage: SeenMessageEntity,
            pendingMessage: PendingMessageEntity,
            pendingAssets: List<PendingAssetEntity>,
        ): Boolean {
            attempts++
            if (throwOnEnqueue) throw IllegalStateException("模拟 outbox 写入失败")
            if (rejectEnqueue || !seen.add(seenMessage.fingerprint)) return false
            pending += pendingMessage
            assets += pendingAssets
            afterNextAcceptedEnqueue?.also { afterNextAcceptedEnqueue = null }?.invoke()
            return true
        }
    }
}
