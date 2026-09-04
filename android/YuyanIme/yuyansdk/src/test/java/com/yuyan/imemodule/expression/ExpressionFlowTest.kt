package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import com.yuyan.imemodule.expression.send.ExpressionFlowController
import com.yuyan.imemodule.expression.send.ExpressionSendController
import com.yuyan.imemodule.expression.send.ExpressionSendResult
import com.yuyan.imemodule.expression.send.ExpressionSender
import com.yuyan.imemodule.expression.send.PreparedExpression
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExpressionFlowTest {
    @Test
    fun `准备完成后立即只发送一次并清空内容`() = runBlocking {
        val sender = RecordingSender()
        val sendController = ExpressionSendController(sender)
        val flow = flow(sendController)

        val result = flow.prepareAndSend(asset("hello", "webp", emptyList()), "你好")

        assertSame(ExpressionSendResult.Sent, result)
        assertEquals(1, sender.calls)
        assertNull(sendController.prepared)
    }

    @Test
    fun `不支持目标和渲染失败返回明确结果且不留待发内容`() = runBlocking {
        val unsupportedController = ExpressionSendController(
            RecordingSender(ExpressionSendResult.UnsupportedTarget),
        )
        val unsupported = flow(unsupportedController).prepareAndSend(
            asset("hello", "webp", emptyList()),
            "你好",
        )
        assertSame(ExpressionSendResult.UnsupportedTarget, unsupported)
        assertNull(unsupportedController.prepared)

        val failedController = ExpressionSendController(RecordingSender())
        val failedFlow = ExpressionFlowController(
            sendController = failedController,
            prepareAsset = { _, _ -> error("渲染失败") },
            prepareCombination = { error("本用例不选择 Emoji") },
        )
        assertEquals(
            ExpressionSendResult.Failed("渲染失败"),
            failedFlow.prepareAndSend(asset("broken", "webp", emptyList()), "你好"),
        )
        assertNull(failedController.prepared)
    }

    @Test
    fun `目标拒绝候选图片时执行一次相册降级并返回可见结果`() = runBlocking {
        val controller = ExpressionSendController(
            RecordingSender(ExpressionSendResult.UnsupportedTarget),
        )
        var fallbackCalls = 0
        var fallbackFile: File? = null
        val flow = ExpressionFlowController(
            sendController = controller,
            prepareAsset = { asset, _ ->
                PreparedExpression(File("/tmp/${asset.id}.webp"), "image/webp")
            },
            prepareCombination = { error("本用例不选择 Emoji") },
            fallback = { expression, failure ->
                fallbackCalls += 1
                fallbackFile = expression.file
                assertSame(ExpressionSendResult.UnsupportedTarget, failure)
                ExpressionSendResult.SavedToGallery
            },
        )

        val result = flow.prepareAndSend(asset("glass-heart", "webp", emptyList()), "玻璃心")

        assertSame(ExpressionSendResult.SavedToGallery, result)
        assertEquals(1, fallbackCalls)
        assertEquals("glass-heart.webp", fallbackFile?.name)
        assertNull(controller.prepared)
    }

    @Test
    fun `准备阶段取消时向上传播且不执行降级`() = runBlocking {
        val controller = ExpressionSendController(RecordingSender())
        var fallbackCalls = 0
        val flow = ExpressionFlowController(
            sendController = controller,
            prepareAsset = { _, _ -> throw CancellationException("用户已切换输入") },
            prepareCombination = { error("本用例不选择 Emoji") },
            fallback = { _, failure -> fallbackCalls += 1; failure },
        )

        try {
            flow.prepareAndSend(asset("cancelled", "webp", emptyList()), "你好")
            fail("应向上传播 CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(0, fallbackCalls)
        assertNull(controller.prepared)
    }

    @Test
    fun `降级阶段取消时向上传播且清理待发内容`() = runBlocking {
        val controller = ExpressionSendController(
            RecordingSender(ExpressionSendResult.UnsupportedTarget),
        )
        val flow = ExpressionFlowController(
            sendController = controller,
            prepareAsset = { asset, _ ->
                PreparedExpression(File("/tmp/${asset.id}.webp"), "image/webp")
            },
            prepareCombination = { error("本用例不选择 Emoji") },
            fallback = { _, _ -> throw CancellationException("界面已关闭") },
        )

        try {
            flow.prepareAndSend(asset("cancelled-fallback", "webp", emptyList()), "你好")
            fail("应向上传播 CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertNull(controller.prepared)
    }

    @Test
    fun `发送阶段取消时向上传播且清理发送状态`() = runBlocking {
        val controller = ExpressionSendController(
            ExpressionSender { throw CancellationException("输入目标已切换") },
        )
        val flow = flow(controller)

        try {
            flow.prepareAndSend(asset("cancelled-send", "webp", emptyList()), "你好")
            fail("应向上传播 CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertNull(controller.prepared)
        assertSame(ExpressionSendResult.NotPrepared, controller.confirm())
    }

    @Test
    fun `外部取消正在挂起的发送仍会清理状态`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val controller = ExpressionSendController(
            ExpressionSender {
                started.complete(Unit)
                awaitCancellation()
            },
        )
        val flow = flow(controller)
        val job = launch {
            flow.prepareAndSend(asset("externally-cancelled", "webp", emptyList()), "你好")
        }

        started.await()
        job.cancelAndJoin()

        assertNull(controller.prepared)
        assertSame(ExpressionSendResult.NotPrepared, controller.confirm())
    }

    @Test
    fun `重复点击在首次发送完成前被忽略`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sender = object : ExpressionSender {
            var calls = 0
            override suspend fun send(expression: PreparedExpression): ExpressionSendResult {
                calls += 1
                started.complete(Unit)
                release.await()
                return ExpressionSendResult.Sent
            }
        }
        val flow = flow(ExpressionSendController(sender))
        val selected = asset("hello", "webp", emptyList())

        val first = async { flow.prepareAndSend(selected, "你好") }
        started.await()
        assertSame(ExpressionSendResult.AlreadySending, flow.prepareAndSend(selected, "你好"))
        release.complete(Unit)

        assertSame(ExpressionSendResult.Sent, first.await())
        assertEquals(1, sender.calls)
    }

    @Test
    fun `候选推荐到 GIF 准备取消和单次确认形成完整链路`() = runBlocking {
        val gif = asset("arrow-gif", "gif", keywords = listOf("放箭"))
        val catalog = ExpressionCatalog(document(templates = listOf(gif)))
        val panel = ExpressionPanelState()
        val sender = RecordingSender()
        val flow = ExpressionFlowController(
            sendController = ExpressionSendController(sender),
            prepareAsset = { asset, _ ->
                PreparedExpression(File("/tmp/${asset.id}.${asset.format}"), "image/gif")
            },
            prepareCombination = { error("本用例不选择 Emoji") },
        )
        val queryCoordinator = ExpressionQueryCoordinator(this, 0) { query ->
            panel.beginQuery(query, requestId = 1)
            panel.applyResults(1, catalog.search(query))
        }

        queryCoordinator.onComposingChanged("放箭")
        delay(1)
        assertTrue(panel.results.isEmpty())

        queryCoordinator.onCommitted("放箭")
        delay(1)
        assertTrue(panel.isVisible)
        assertEquals("arrow-gif", panel.results.single().id)

        flow.prepare(panel.results.single(), requireNotNull(panel.query))
        flow.cancel()
        assertEquals(0, sender.calls)

        flow.prepare(panel.results.single(), requireNotNull(panel.query))
        assertEquals(ExpressionSendResult.Sent, flow.confirm())
        assertEquals(ExpressionSendResult.NotPrepared, flow.confirm())
        assertEquals(1, sender.calls)
        queryCoordinator.close()
    }

    @Test
    fun `Emoji 正反顺序准备不同 WebP`() = runBlocking {
        val catalog = ExpressionCatalog(
            document(
                combinations = listOf(
                    combination("angry", "cry"),
                    combination("cry", "angry"),
                ),
            ),
        )
        val preparedFiles = mutableListOf<File>()
        val flow = ExpressionFlowController(
            sendController = ExpressionSendController(RecordingSender()),
            prepareAsset = { _, _ -> error("本用例不选择模板") },
            prepareCombination = { combination ->
                PreparedExpression(File("/tmp/${combination.key}.webp"), "image/webp")
                    .also { preparedFiles += it.file }
            },
        )

        flow.prepare(requireNotNull(catalog.findCombination("angry", "cry")))
        flow.prepare(requireNotNull(catalog.findCombination("cry", "angry")))

        assertEquals("angry__cry.webp", preparedFiles[0].name)
        assertEquals("cry__angry.webp", preparedFiles[1].name)
        assertNotEquals(preparedFiles[0], preparedFiles[1])
    }

    private fun flow(sendController: ExpressionSendController) = ExpressionFlowController(
        sendController = sendController,
        prepareAsset = { asset, _ ->
            PreparedExpression(File("/tmp/${asset.id}.${asset.format}"), "image/${asset.format}")
        },
        prepareCombination = { error("本用例不选择 Emoji") },
    )

    private class RecordingSender(
        private val result: ExpressionSendResult = ExpressionSendResult.Sent,
    ) : ExpressionSender {
        var calls = 0
        override suspend fun send(expression: PreparedExpression): ExpressionSendResult {
            calls += 1
            return result
        }
    }

    private fun asset(
        id: String,
        format: String,
        keywords: List<String>,
    ) = ExpressionAsset(
        id = id,
        type = "synthesis-template",
        format = format,
        version = "v1",
        fileName = "templates/$id.$format",
        sha256 = id.padEnd(64, 'a').take(64),
        width = 512,
        height = 512,
        keywords = keywords,
    )

    private fun combination(first: String, second: String) = EmojiCombination(
        key = "${first}__${second}",
        firstId = first,
        secondId = second,
        fileName = "emoji-combinations/${first}__${second}.webp",
        sha256 = (first + second).padEnd(64, 'b').take(64),
        version = "v1",
        width = 256,
        height = 256,
    )

    private fun document(
        templates: List<ExpressionAsset> = emptyList(),
        combinations: List<EmojiCombination> = emptyList(),
    ) = ExpressionCatalogDocument(
        version = "v1",
        templates = templates,
        emojiBases = emptyList(),
        emojiCombinations = combinations,
    )
}
