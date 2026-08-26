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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionFlowTest {
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

    private class RecordingSender : ExpressionSender {
        var calls = 0
        override suspend fun send(expression: PreparedExpression): ExpressionSendResult {
            calls += 1
            return ExpressionSendResult.Sent
        }
    }

    private fun asset(
        id: String,
        format: String,
        keywords: List<String>,
    ) = ExpressionAsset(
        id = id,
        type = "template",
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
