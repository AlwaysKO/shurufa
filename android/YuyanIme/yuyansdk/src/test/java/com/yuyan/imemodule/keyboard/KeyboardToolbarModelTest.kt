package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardToolbarModelTest {

    @Test
    fun `空工具栏只提供三个固定动作并用不可点击槽保持AI位置`() {
        val output = KeyboardToolbarModel.merge(emptyList())

        assertEquals(5, output.size)
        assertEquals(
            listOf(SkbMenuMode.Emojicon, SkbMenuMode.QuickKeyboard, SkbMenuMode.AiDoutu),
            output.mapNotNull { it.skbMenuMode },
        )
        assertNull(output[2].skbMenuMode)
        assertNull(output[3].skbMenuMode)
        assertEquals(SkbMenuMode.AiDoutu, output[4].skbMenuMode)
    }

    @Test
    fun `只有一个非固定项时不伪造第二个菜单动作`() {
        val output = KeyboardToolbarModel.merge(listOf(SkbMenuMode.ClipBoard))

        assertEquals(SkbMenuMode.ClipBoard, output[2].skbMenuMode)
        assertNull(output[3].skbMenuMode)
        assertEquals(SkbMenuMode.AiDoutu, output[4].skbMenuMode)
        assertEquals(
            listOf(
                SkbMenuMode.Emojicon,
                SkbMenuMode.QuickKeyboard,
                SkbMenuMode.ClipBoard,
                SkbMenuMode.AiDoutu,
            ),
            output.mapNotNull { it.skbMenuMode },
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
        val modes = output.mapNotNull { it.skbMenuMode }

        assertEquals(SkbMenuMode.Emojicon, output[0].skbMenuMode)
        assertEquals(SkbMenuMode.QuickKeyboard, output[1].skbMenuMode)
        assertEquals(SkbMenuMode.AiDoutu, output[4].skbMenuMode)
        assertEquals(1, modes.count { it == SkbMenuMode.Emojicon })
        assertEquals(1, modes.count { it == SkbMenuMode.QuickKeyboard })
        assertEquals(1, modes.count { it == SkbMenuMode.AiDoutu })
    }

    @Test
    fun `语音和手写只有已存在时才优先进入高频槽位`() {
        val existing = listOf(
            SkbMenuMode.ClipBoard,
            SkbMenuMode.PinyinHandWriting,
            SkbMenuMode.Phrases,
            SkbMenuMode.Voice,
            SkbMenuMode.TextEdit,
        )

        val output = KeyboardToolbarModel.merge(existing)

        assertEquals(
            listOf(SkbMenuMode.Voice, SkbMenuMode.PinyinHandWriting),
            output.subList(2, 4).mapNotNull { it.skbMenuMode },
        )
        assertEquals(
            listOf(SkbMenuMode.ClipBoard, SkbMenuMode.Phrases, SkbMenuMode.TextEdit),
            output.drop(5).mapNotNull { it.skbMenuMode },
        )
        assertEquals(existing.toSet(), output.mapNotNull { it.skbMenuMode }.filterNot(::isPinned).toSet())
    }

    @Test
    fun `只有低频项时按原顺序补位并保留其余项目`() {
        val existing = listOf(SkbMenuMode.Phrases, SkbMenuMode.TextEdit, SkbMenuMode.ClipBoard)

        val output = KeyboardToolbarModel.merge(existing)

        assertEquals(
            listOf(SkbMenuMode.Phrases, SkbMenuMode.TextEdit),
            output.subList(2, 4).mapNotNull { it.skbMenuMode },
        )
        assertEquals(
            listOf(SkbMenuMode.ClipBoard),
            output.drop(5).mapNotNull { it.skbMenuMode },
        )
        assertFalse(output.any { it.skbMenuMode == SkbMenuMode.Voice })
        assertFalse(output.any { it.skbMenuMode == SkbMenuMode.PinyinHandWriting })
    }

    @Test
    fun `旧版手写动作同样只在已存在时视为高频能力`() {
        val output = KeyboardToolbarModel.merge(
            listOf(SkbMenuMode.Settings, SkbMenuMode.Handwriting, SkbMenuMode.ClipBoard),
        )

        assertEquals(SkbMenuMode.Handwriting, output[2].skbMenuMode)
        assertEquals(SkbMenuMode.Settings, output[3].skbMenuMode)
        assertEquals(listOf(SkbMenuMode.ClipBoard), output.drop(5).mapNotNull { it.skbMenuMode })
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
        val modes = output.mapNotNull { it.skbMenuMode }

        assertEquals(snapshot, existing)
        assertNotSame(existing, output)
        assertEquals(2, modes.count { it == SkbMenuMode.ClipBoard })
        assertEquals(
            listOf(
                SkbMenuMode.ClipBoard,
                SkbMenuMode.ClipBoard,
                SkbMenuMode.Phrases,
                SkbMenuMode.TextEdit,
            ),
            output.drop(5).mapNotNull { it.skbMenuMode },
        )
    }

    @Test
    fun `占位槽不是可持久化菜单枚举而旧名称仍可解码`() {
        val enumNames = SkbMenuMode.entries.map(SkbMenuMode::name)

        assertFalse("Placeholder" in enumNames)
        assertEquals(SkbMenuMode.Emojicon, SkbMenuMode.decode("Emojicon"))
        assertEquals(SkbMenuMode.Voice, SkbMenuMode.decode("Voice"))
        assertEquals("QuickKeyboard", SkbMenuMode.QuickKeyboard.name)
        assertEquals("AiDoutu", SkbMenuMode.AiDoutu.name)
    }

    private fun isPinned(mode: SkbMenuMode): Boolean = mode == SkbMenuMode.Emojicon ||
        mode == SkbMenuMode.QuickKeyboard || mode == SkbMenuMode.AiDoutu
}
