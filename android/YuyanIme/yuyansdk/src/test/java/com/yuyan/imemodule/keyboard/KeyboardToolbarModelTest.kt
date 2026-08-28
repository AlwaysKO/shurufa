package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class KeyboardToolbarModelTest {

    @Test
    fun `空工具栏仍提供截图对应的五个核心入口`() {
        assertEquals(
            listOf(
                SkbMenuMode.Emojicon,
                SkbMenuMode.QuickKeyboard,
                SkbMenuMode.Voice,
                SkbMenuMode.PinyinHandWriting,
                SkbMenuMode.AiDoutu,
            ),
            KeyboardToolbarModel.merge(emptyList()),
        )
    }

    @Test
    fun `固定入口只出现一次且处在固定槽位`() {
        val output = KeyboardToolbarModel.merge(
            listOf(
                SkbMenuMode.AiDoutu,
                SkbMenuMode.Emojicon,
                SkbMenuMode.QuickKeyboard,
                SkbMenuMode.ClipBoard,
                SkbMenuMode.Emojicon,
                SkbMenuMode.AiDoutu,
            ),
        )

        assertEquals(SkbMenuMode.Emojicon, output[0])
        assertEquals(SkbMenuMode.QuickKeyboard, output[1])
        assertEquals(SkbMenuMode.AiDoutu, output[4])
        assertEquals(1, output.count { it == SkbMenuMode.Emojicon })
        assertEquals(1, output.count { it == SkbMenuMode.QuickKeyboard })
        assertEquals(1, output.count { it == SkbMenuMode.AiDoutu })
    }

    @Test
    fun `语音和手写优先进入AI之前的两个高频槽位`() {
        val output = KeyboardToolbarModel.merge(
            listOf(
                SkbMenuMode.ClipBoard,
                SkbMenuMode.PinyinHandWriting,
                SkbMenuMode.Phrases,
                SkbMenuMode.Voice,
                SkbMenuMode.TextEdit,
            ),
        )

        assertEquals(
            listOf(SkbMenuMode.Voice, SkbMenuMode.PinyinHandWriting),
            output.subList(2, 4),
        )
        assertEquals(
            listOf(SkbMenuMode.ClipBoard, SkbMenuMode.Phrases, SkbMenuMode.TextEdit),
            output.drop(5),
        )
    }

    @Test
    fun `旧版手写动作同样视为高频能力`() {
        val output = KeyboardToolbarModel.merge(
            listOf(SkbMenuMode.Settings, SkbMenuMode.Handwriting, SkbMenuMode.ClipBoard),
        )

        assertEquals(SkbMenuMode.Handwriting, output[2])
        assertEquals(SkbMenuMode.Settings, output[3])
        assertEquals(listOf(SkbMenuMode.ClipBoard), output.drop(5))
    }

    @Test
    fun `高频能力缺失时按原顺序使用现有项目补位`() {
        val output = KeyboardToolbarModel.merge(
            listOf(SkbMenuMode.Phrases, SkbMenuMode.TextEdit, SkbMenuMode.ClipBoard),
        )

        assertEquals(
            listOf(SkbMenuMode.Phrases, SkbMenuMode.TextEdit),
            output.subList(2, 4),
        )
        assertEquals(listOf(SkbMenuMode.ClipBoard), output.drop(5))
    }

    @Test
    fun `非固定重复项目完整保留且尾部相对顺序稳定`() {
        val existing = mutableListOf(
            SkbMenuMode.Voice,
            SkbMenuMode.PinyinHandWriting,
            SkbMenuMode.ClipBoard,
            SkbMenuMode.ClipBoard,
            SkbMenuMode.Phrases,
            SkbMenuMode.TextEdit,
        )
        val snapshot = existing.toList()

        val output = KeyboardToolbarModel.merge(existing)

        assertEquals(snapshot, existing)
        assertNotSame(existing, output)
        assertEquals(2, output.count { it == SkbMenuMode.ClipBoard })
        assertEquals(
            listOf(
                SkbMenuMode.ClipBoard,
                SkbMenuMode.ClipBoard,
                SkbMenuMode.Phrases,
                SkbMenuMode.TextEdit,
            ),
            output.drop(5),
        )
    }

    @Test
    fun `菜单枚举仍按名称持久化并可解码旧值`() {
        assertEquals(SkbMenuMode.Emojicon, SkbMenuMode.decode("Emojicon"))
        assertEquals(SkbMenuMode.Voice, SkbMenuMode.decode("Voice"))
        assertEquals("QuickKeyboard", SkbMenuMode.QuickKeyboard.name)
        assertEquals("AiDoutu", SkbMenuMode.AiDoutu.name)
    }
}
