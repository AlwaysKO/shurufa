package com.yuyan.imemodule.data.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSelectionSmallPageTest {
    @Test fun `首屏重排和手工词不改变空白过滤页之后的全局原生索引`() {
        val pageSize = 16
        val filteredPage = List(pageSize) { "blocked-$it" }
        val selection = CandidateSelection(
            listOf(RankedCandidate("手工词"), RankedCandidate("首屏末项", nativeIndex = 15),
                RankedCandidate("首屏次项", nativeIndex = 1)),
            nativeCount = pageSize,
            blockedTexts = filteredPage.toSet(),
        )

        assertTrue(selection.appendNativePage(filteredPage, "").isEmpty())
        val thirdPage = List(pageSize) { "third-$it" }
        assertEquals(thirdPage.indices.toList(), selection.appendNativePage(thirdPage, ""))

        assertNull(selection.at(0)?.nativeIndex)
        assertEquals(15, selection.at(1)?.nativeIndex)
        assertEquals(1, selection.at(2)?.nativeIndex)
        thirdPage.forEachIndexed { offset, text ->
            val candidate = selection.at(3 + offset)
            assertEquals(text, candidate?.text)
            assertEquals(2 * pageSize + offset, candidate?.nativeIndex)
        }
    }

    @Test fun `跨页禁词和新禁词只移除展示项不压缩原生选择索引`() {
        val pageSize = 16
        val hiddenPage = List(pageSize) { "hidden-$it" }
        val thirdPage = List(pageSize) { "third-$it" }
        val selection = CandidateSelection(
            listOf(RankedCandidate("首屏禁词", nativeIndex = 4), RankedCandidate("首屏词", nativeIndex = 7)),
            nativeCount = pageSize,
            blockedTexts = hiddenPage.toSet() + setOf("首屏禁词", thirdPage[2]),
        )

        assertEquals(listOf("首屏词"), selection.firstPage.map { it.text })
        assertTrue(selection.appendNativePage(hiddenPage, "").isEmpty())
        val visible = selection.appendNativePage(thirdPage, "", latestBlockedTexts = setOf(thirdPage[5]))
        assertEquals(thirdPage.indices.filter { it != 2 && it != 5 }, visible)
        visible.forEachIndexed { displayOffset, nativeOffset ->
            assertEquals(thirdPage[nativeOffset], selection.at(1 + displayOffset)?.text)
            assertEquals(2 * pageSize + nativeOffset, selection.at(1 + displayOffset)?.nativeIndex)
        }
    }

    @Test fun `拼写过滤整页及不足一页的末页仍按实际原生数量推进索引`() {
        val selection = CandidateSelection(emptyList(), nativeCount = 16)
        val filteredPage = List(16) { "美国最高法院" }
        assertTrue(selection.appendNativePage(filteredPage, "649439").isEmpty())
        val lastPage = listOf("美国最高法院", "你好", "美国最高法院", "你这样", "美国最高法院")
        val readings = listOf("mei guo zui gao fa yuan", "ni hao", "mei guo zui gao fa yuan", "ni zhe yang", "mei guo zui gao fa yuan")
        // 无注释的兼容入口按现有长度规则过滤；选中项保留其所在原生页的偏移。
        assertEquals(listOf(1, 3), selection.appendNativePage(lastPage, "649439"))
        assertEquals("你好", selection.at(0)?.text)
        assertEquals(33, selection.at(0)?.nativeIndex)
        assertEquals("你这样", selection.at(1)?.text)
        assertEquals(35, selection.at(1)?.nativeIndex)
        assertEquals(listOf(0), selection.appendNativePage(listOf("你这样"), "649439", listOf(readings[3])))
        assertEquals(37, selection.at(2)?.nativeIndex)
        assertEquals("ni zhe yang", selection.at(2)?.pinyin)
    }
}
