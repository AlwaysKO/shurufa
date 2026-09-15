package com.yuyan.inputmethod

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import java.util.zip.GZIPInputStream
import com.yuyan.imemodule.data.completion.T9Lexicon
import com.yuyan.imemodule.data.completion.CandidateSelection
import com.yuyan.imemodule.data.completion.RankedCandidate
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.data.InputKey
import com.yuyan.inputmethod.data.KeyRecordStack
import com.yuyan.inputmethod.util.T9Spelling
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class T9FullCompositionDisplayTest {
    private val stack = RimeEngine::class.java.getDeclaredField("keyRecordStack").run {
        isAccessible = true; get(RimeEngine) as KeyRecordStack
    }
    @Before fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Launcher::class.java.getDeclaredField("context").apply { isAccessible = true; set(Launcher.instance, context) }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        InputModeSwitcher::class.java.getDeclaredField("mInputMode").apply {
            isAccessible = true; setInt(InputModeSwitcher, InputModeSwitcher.MODE_T9_CHINESE)
        }
        RimeEngine.clearCachedCompositionForSchemaSwitch()
        stack.clear()
    }
    @After fun after() { stack.clear(); RimeEngine.clearCachedCompositionForSchemaSwitch(); InputModeSwitcher.reset() }

    private fun composition(code: String, prefix: String, rawReadings: List<String>) {
        stack.clear()
        // JVM键盘映射不等于手机CAPS_LOCK事件；此处注入生产栈记录，测试的是显示链路。
        @Suppress("UNCHECKED_CAST")
        val records = KeyRecordStack::class.java.getDeclaredField("keyRecords").run {
            isAccessible = true; get(stack) as MutableList<InputKey>
        }
        code.forEach { records.add(InputKey.T9Key("ADGJMPTW"[it - '2'])) }
        assertEquals(code, stack.unlockedT9Digits())
        RimeEngine.showComposition = T9Spelling.preedit(code, prefix) ?: code
        RimeEngine.showCandidates = listOf(CandidateListItem(prefix, "我不在"))
        RimeEngine::class.java.getDeclaredField("nativeCandidateMetadata").apply {
            isAccessible = true
            set(RimeEngine, CandidateSelection(rawReadings.mapIndexed { index, reading -> RankedCandidate("原生候选$index", reading, index) }, rawReadings.size))
        }
    }

    @Test fun `选词前显示整段拼音但不改变提交缓存和候选`() {
        composition("9628924726448264", "wo bu zai", listOf("wo bu zai pang huang", "wo bu zai"))
        val original = RimeEngine.showComposition
        val candidates = RimeEngine.showCandidates
        assertEquals("wo'bu'zai'pang'huang", DecodingInfo.composingStrForDisplay)
        assertEquals(original, RimeEngine.showComposition)
        assertEquals("wobuzai726448264", DecodingInfo.composingStrForCommit)
        assertTrue(DecodingInfo.hasUnresolvedT9Composition)
        assertSame(candidates, RimeEngine.showCandidates)
        assertEquals(0, RimeEngine.candidateForSelection(0)?.nativeIndex)
    }

    @Test fun `末字未打完也保留已输入的后半段且不扩写`() {
        for ((code, expected) in listOf(
            "962892472644" to "wo'bu'zai'pang'h",
            "6464842678727" to "ming'tian'qu'pa's",
            "6464842678727426" to "ming'tian'qu'pa'shan",
        )) {
            val reading = if (code.startsWith("96")) "wo bu zai pang huang" else "ming tian qu pa shan"
            val prefix = if (code.startsWith("96")) "wo bu zai" else "ming tian"
            composition(code, prefix, listOf(reading, prefix))
            assertEquals(expected, DecodingInfo.composingStrForDisplay)
        }
    }

    @Test fun `显示不覆盖已经完整匹配的个人首选读音`() {
        composition("64946453", "ni ying le", listOf("ni xing le", "ni ying le"))
        assertEquals("ni'ying'le", DecodingInfo.composingStrForDisplay)
    }

    @Test fun `输入s时缺少尾部候选读音继续sh后恢复也不会闪键帽标签`() {
        val prefix = "wo dang shi xiang"
        val base = T9Lexicon.digits(prefix.replace(" ", ""))
        for (tail in listOf("7", "74", "7", "74", "748")) {
            val code = base + tail
            composition(code, prefix, if (tail == "7") listOf(prefix) else listOf("$prefix shu", prefix))
            val raw = RimeEngine.showComposition
            val displayed = DecodingInfo.composingStrForDisplay
            assertTrue(displayed, displayed.all { it in 'a'..'z' || it == '\'' })
            assertEquals(code, T9Lexicon.digits(displayed.replace("'", "")))
            assertEquals(raw, RimeEngine.showComposition)
            assertTrue(DecodingInfo.hasUnresolvedT9Composition)
        }
    }

    @Test fun `未对齐的读音不能冒充整段拼音也不能隐藏剩余按键`() {
        composition("96353", "wo", listOf("ni hao ma", "wo"))
        assertEquals("wo'e'ke", DecodingInfo.composingStrForDisplay)
        assertTrue(DecodingInfo.hasUnresolvedT9Composition)
    }

    @Test fun `分段锁定后保留已选汉字且清理状态不留下旧后缀`() {
        composition("9628924726448264", "wo bu zai", listOf("wo bu zai pang huang"))
        stack.pushCandidateSelectAction()
        RimeEngine.showComposition = "我不在pang'guang"
        assertEquals("我不在pang'guang", DecodingInfo.composingStrForDisplay)
        stack.clear(); RimeEngine.clearCachedCompositionForSchemaSwitch()
        assertEquals("", DecodingInfo.composingStrForDisplay)
    }
    @Test fun `手机录制逐键及历史反馈组合的可见拼音覆盖全部按键`() {
        val lines = GZIPInputStream(javaClass.getResourceAsStream("/t9-full-display-native-replay.tsv.gz")!!)
            .bufferedReader().use { it.readLines() }
        var cursor = 0
        var cases = 0
        while (cursor < lines.size) {
            val header = lines[cursor++].split('\t')
            assertEquals("CASE", header[0])
            val readings = (0 until header[3].toInt()).map {
                val row = lines[cursor++].split('\t')
                assertEquals("READING", row[0]); row[1]
            }
            val code = header[1]
            composition(code, "", readings)
            RimeEngine.showComposition = header[2]
            val displayed = DecodingInfo.composingStrForDisplay
            // 本批录制中引擎有整段读音，不需要未解析按键兜底；拼音必须可还原全部码。
            assertEquals("$code 显示丢失/扩写: $displayed", code,
                T9Lexicon.digits(displayed.replace("'", "").replace(" ", "")))
            assertEquals(header[2], RimeEngine.showComposition)
            cases++
        }
        assertEquals(29, cases)
    }

}
