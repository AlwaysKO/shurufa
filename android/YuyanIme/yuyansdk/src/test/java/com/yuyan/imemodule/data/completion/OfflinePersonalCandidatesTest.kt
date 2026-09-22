package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflinePersonalCandidatesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun closeStore() {
        val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close()
        field.set(OfflineT9Candidates, null)
    }
    @Before fun setup() {
        closeStore()
        context.deleteDatabase("local_input.db")
        OfflineT9Candidates.init(context)
    }
    @After fun cleanup() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, null)
        }
        closeStore()
        context.deleteDatabase("local_input.db")
    }
    @Test fun `一两键学习只提升当前原生候选并保留重复字索引与分页`() {
        for (code in listOf("3", "62")) {
            OfflineT9Candidates.learn(code, "的")
            repeat(5) { OfflineT9Candidates.learn(code, "不存在") }
            val result = OfflineT9Candidates.select(code, listOf("得", "的", "的"), listOf("de", "de", "di"))
            assertEquals(listOf("的", "的", "得"), result.firstPage.map { it.text })
            assertEquals(listOf(1, 2, 0), result.firstPage.map { it.nativeIndex })
            assertEquals(3, result.firstPage.size)
        }
        assertEquals(listOf("得", "的"), OfflineT9Candidates.select("4", listOf("得", "的")).firstPage.map { it.text })
        val db = LocalInputStore(context)
        try {
            assertEquals(1L, db.learned("3").first { it.text == "的" }.count)
            assertTrue(db.dictionaryExport().any { it.code == "62" && it.text == "的" })
        } finally { db.close() }
    }

    @Test fun `分段学习后的鲮在锁音原生列表内提升且不注入其他历史词`() {
        val tracker = T9CommitTracker()
        repeat(3) {
            tracker.segment("8245464", "泰", "tai", null)
            tracker.segment("", "鲮", "ling", "泰鲮")
            OfflineT9Candidates.learn(requireNotNull(tracker.consumeSelection("泰鲮", true)))
        }
        repeat(8) { OfflineT9Candidates.learn("5464", "京", "jing") }
        val native = listOf(RankedCandidate("令", "ling", 0), RankedCandidate("鲮", "ling", 25))
        val selected = OfflineT9Candidates.rankNative(native, 100)
        assertEquals(listOf("鲮", "令"), selected.firstPage.map { it.text })
        assertEquals(25, selected.at(0)?.nativeIndex)
        assertEquals(listOf(0), selected.appendNativePage(listOf("另"), "", listOf("ling")))
        assertEquals(100, selected.at(2)?.nativeIndex)
        closeStore()
        OfflineT9Candidates.init(context)
        assertEquals("泰鲮", OfflineT9Candidates.select("8245464", emptyList(), emptyList()).firstPage.first().text)
        assertEquals("鲮", OfflineT9Candidates.rankNative(native, 100).firstPage.first().text)
    }

    @Test fun `锁音重排按每条实际读音取证不混入同字异读记录`() {
        repeat(8) { OfflineT9Candidates.learn("9464", "行", "xing") }
        val native = listOf(RankedCandidate("航", "hang", 0), RankedCandidate("行", "hang", 1))
        assertEquals(native, OfflineT9Candidates.rankNative(native, 2).firstPage)
        // 即使某页同字有两种读音，原生索引和各自读音也不应去重混合。
        val mixed = native + RankedCandidate("行", "xing", 2)
        assertEquals(2, OfflineT9Candidates.rankNative(mixed, 3).firstPage.first().nativeIndex)
        assertEquals(3, OfflineT9Candidates.rankNative(mixed, 3).firstPage.size)
    }

    @Test fun `一次整词选择不伪造逐字点击`() {
        val tracker = T9CommitTracker()
        tracker.segment("8245464", "泰鲮", "tai ling", "泰鲮")
        OfflineT9Candidates.learn(requireNotNull(tracker.consumeSelection("泰鲮", true)))
        val db = LocalInputStore(context)
        try {
            assertEquals(1L, db.learned("8245464").single().count)
            assertTrue(db.learned("824").isEmpty())
            assertTrue(db.learned("5464").isEmpty())
        } finally { db.close() }
    }

    @Test fun `兼容完整码的最近时间不冒充三键同码改选`() {
        val db = LocalInputStore(context)
        try {
            db.learn("966", "我哦", pinyin = "wo o")
            db.writableDatabase.execSQL("UPDATE learned_input SET last_used=last_used-60000 WHERE text='我哦'")
            repeat(8) { db.learn("96636", "我们", pinyin = "wo men") }
            repeat(2) {
                assertEquals("我哦", OfflineT9Candidates.select("966", listOf("我哦", "我们"),
                    listOf("wo o", "wo men")).firstPage.first().text)
            }
            assertEquals(1L, db.learned("966").single().count)
            assertEquals(8L, db.learned("96636").single().count)
        } finally { db.close() }
    }

    @Test fun `锁音最近改选胜过高频旧项且同字异读索引分别保留`() {
        val db = LocalInputStore(context)
        try {
            repeat(8) { db.learn("9464", "星", pinyin = "xing") }
            db.writableDatabase.execSQL("UPDATE learned_input SET last_used=last_used-60000 WHERE text='星'")
            db.learn("9464", "行", pinyin = "xing")
        } finally { db.close() }
        val native = listOf(RankedCandidate("星", "xing", 0), RankedCandidate("行", "hang", 3),
            RankedCandidate("行", "xing", 7))
        val result = OfflineT9Candidates.rankNative(native, 8)
        assertEquals(7, result.firstPage.first().nativeIndex)
        assertEquals(3, result.firstPage.size)
        assertEquals(listOf(0), result.appendNativePage(listOf("型"), "", listOf("xing")))
        assertEquals(8, result.at(3)?.nativeIndex)
    }

    @Test fun `同码最近改选胜过旧高频首项并在重开后保持`() {
        val db = LocalInputStore(context)
        try {
            repeat(4) { db.learn("966", "我哦", pinyin = "wo o") }
            db.writableDatabase.execSQL("UPDATE learned_input SET last_used=last_used-60000 WHERE text='我哦'")
            db.learn("966", "我们", pinyin = "wo men")
        } finally { db.close() }
        closeStore()
        OfflineT9Candidates.init(context)
        val result = OfflineT9Candidates.select("966", listOf("我哦", "我们"), listOf("wo o", "wo men"))
        assertEquals("我们", result.firstPage.first().text)
        assertEquals(1, result.firstPage.first().nativeIndex)
    }

    @Test fun `完整码学习可在合法三键末音节补全使用且不复制次数`() {
        repeat(5) { OfflineT9Candidates.learn("96636", "我们", "wo men") }
        val db = LocalInputStore(context)
        try {
            assertTrue(db.relatedLearned("966").any { it.code == "96636" && it.choice.text == "我们" })
            assertTrue(db.learned("966").isEmpty())
        } finally { db.close() }
        assertEquals("我们", OfflineT9Candidates.select("966", listOf("我哦"), listOf("wo o")).firstPage.first().text)
    }

    @Test fun `三键补回的常用词不应被原生一百个单字挤出前排`() {
        val native = listOf("我哦") + (0 until 99).map { (0x4e00 + it).toChar().toString() }
        val comments = listOf("wo o") + List(99) { "wo" }
        val result = OfflineT9Candidates.select("966", native, comments).firstPage
        assertTrue(result.indexOfFirst { it.text == "我们" } in 0..7)
        assertEquals("我们", result.first().text) // 常用词优先，不按文字拉黑其他合法候选。
    }

    @Test fun `三键wo加m可独立召回我们而不依赖原生首屏`() {
        val result = OfflineT9Candidates.select("966", listOf("我哦", "我"), listOf("wo o", "wo"))
        assertTrue(result.firstPage.any { it.text == "我们" && it.pinyin == "wo men" })
        // wo o 同样匹配966；本任务保证我们可召回，不新增文字黑名单。
    }

    @Test fun `词性歧义的本饿不能进入首屏和后页`() {
        val result = OfflineT9Candidates.select("2363", listOf("本饿", "本"), listOf("ben e", "ben"))
        assertFalse(result.firstPage.any { it.text == "本饿" })
        assertTrue(result.appendNativePage(listOf("本饿"), "2363", listOf("ben e")).isEmpty())
    }

    @Test fun `既有可信整词补全优先但明确学习仍可改变首位`() {
        val code = "24664342622"
        val native = listOf("充电吧", "充电")
        val comments = listOf("chong dian ba", "chong dian")
        val before = OfflineT9Candidates.select(code, native, comments).firstPage
        assertEquals("充电宝", before.first().text)
        // 公开原词可以低位出现，但既有“充电宝”仍须第一。
        assertTrue(before.any { it.text == "充电吧" })
        repeat(3) { OfflineT9Candidates.learn(code, "充电吧", "chong dian ba") }
        assertEquals("充电吧", OfflineT9Candidates.select(code, native, comments).firstPage.first().text)
    }

    @Test fun `保留不自然原生句的观测且首屏后页资格必须一致`() {
        // 用户接受未知整句偶尔用词不准后，不再以词性判定以下句子永远禁止。
        // 保留反例与真实结果，不能把新增候选数当成语义改善数。
        for ((code, text, reading) in listOf(
            Triple("244326536", "吃饭肯", "chi fan ken"),
            Triple("582432654253", "快到嫁了", "kuai dao jia le"),
            Triple("9426424448648", "想吃过户", "xiang chi guo hu"),
            Triple("789267426548", "塑造漂流", "su zao piao liu"),
            Triple("78926742654264", "塑造漂亮", "su zao piao liang"),
            Triple("968342653454", "有点累计", "you dian lei ji"),
        )) {
            val selection = OfflineT9Candidates.select(code, listOf(text), listOf(reading))
            val candidate = selection.firstPage.firstOrNull { it.text == text }
            println("NATIVE_SENTENCE_COUNTEREXAMPLE\t$code\t$text\t${selection.firstPage.joinToString("|") { it.text }}")
            if (candidate != null) {
                assertEquals(0, candidate.nativeIndex)
                assertTrue(code in com.yuyan.inputmethod.util.T9Spelling.completionCodes(candidate.pinyin))
            }
            assertEquals(text, candidate != null, selection.appendNativePage(listOf(text), code, listOf(reading)).isNotEmpty())
        }
    }

    @Test fun `空历史正常短句原生首选不能被整词白名单误删`() {
        for ((code, text, reading) in listOf(
            Triple("96353", "我饿了", "wo e le"),
            Triple("94363362", "真的吗", "zhen de ma"),
            Triple("9267426548", "玩漂流", "wan piao liu"),
        )) {
            val result = OfflineT9Candidates.select(code, listOf(text, "我"), listOf(reading, "wo"))
            assertEquals(code, text, result.firstPage.firstOrNull()?.text)
            assertEquals(0, result.firstPage.first().nativeIndex)
            assertEquals(listOf(0), result.appendNativePage(listOf(text), code, listOf(reading)))
            assertEquals(2, result.at(result.firstPage.size)?.nativeIndex)
        }
    }

    @Test fun `短句依据不能绕过内部简拼和附加符号限制`() {
        assertFalse(OfflineT9Candidates.select("9353", listOf("我饿了"), listOf("wo e le"))
            .firstPage.any { it.text == "我饿了" })
        assertFalse(OfflineT9Candidates.select("96353", listOf("我饿了😀"), listOf("wo e le"))
            .firstPage.any { it.text == "我饿了😀" })
    }

    @Test fun `固定九十八个短句二百六十六码三次学习重开后独立召回首位`() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("".reader()))
        }
        val rows = javaClass.getResourceAsStream("/t9-personal-rehearsal.tsv")!!.bufferedReader().use { it.readLines() }
        assertEquals(266, rows.size)
        assertEquals(98, rows.map { it.split('\t')[1] }.toSet().size)
        for (row in rows) {
            val (code, text, fullCode, reading) = row.split('\t')
            assertEquals(fullCode, T9Lexicon.digits(reading.replace(" ", "")))
            closeStore()
            context.deleteDatabase("local_input.db")
            OfflineT9Candidates.init(context)
            repeat(3) { OfflineT9Candidates.learn(fullCode, text, reading) }
            closeStore()
            OfflineT9Candidates.init(context)
            repeat(2) {
                assertEquals(row, text, OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage.firstOrNull()?.text)
            }
            val db = LocalInputStore(context)
            try { assertEquals(row, 3L, db.learned(fullCode).single().count) } finally { db.close() }
        }
    }

    @Test fun `词库外个人词携带读音后原生空候选也能召回`() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("".reader()))
        }
        repeat(3) { OfflineT9Candidates.learn("94363362", "真的吗", "zhen de ma") }
        closeStore()
        OfflineT9Candidates.init(context)
        for (code in listOf("9436336", "94363362")) {
            val result = OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage
            assertEquals("真的吗", result.firstOrNull()?.text)
            assertEquals("zhen de ma", result.first().pinyin)
        }
        assertTrue(OfflineT9Candidates.select("9362", emptyList(), emptyList()).firstPage.isEmpty())
        val db = LocalInputStore(context)
        try {
            assertEquals(3L, db.learned("94363362").single().count)
            assertTrue(db.learned("9436336").isEmpty())
        } finally { db.close() }
    }

    @Test fun `未知中文拼接不能靠附加符号绕过但纯表情与单字仍可用`() {
        val result = OfflineT9Candidates.select("9664337",
            listOf("总额而😀", "😀", "用"), listOf("zong'e'er", "", "yong"))
        assertFalse(result.firstPage.any { it.text == "总额而😀" })
        assertTrue(result.firstPage.any { it.text == "😀" })
        assertTrue(result.firstPage.any { it.text == "用" })
        assertEquals(listOf(1), result.appendNativePage(listOf("总额而😀", "😀"), "9664337", listOf("zong'e'er", "")))
    }

    @Test fun `可信同码整词按词频排序且首屏后页都拒绝总额而拼接`() {
        val code = "9664337"
        val selection = OfflineT9Candidates.select(code,
            listOf("总额而", "总额", "用"), listOf("zong'e'er", "zong'e", "yong"))
        assertEquals("用的是", selection.firstPage.first().text) // 新公共词源中更常用的同码表达。
        assertTrue(selection.firstPage.any { it.text == "用得上" })
        assertFalse(selection.firstPage.any { it.text == "总额而" })
        assertEquals(1, selection.firstPage.first { it.text == "总额" }.nativeIndex)
        assertEquals(listOf(1, 2), selection.appendNativePage(
            listOf("总额而", "总额", "用"), code, listOf("zong'e'er", "zong'e", "yong")))
        assertEquals(4, selection.at(selection.firstPage.size)?.nativeIndex)
    }

    @Test fun `固定词库不足时回退原生整句但仍保留分段候选与索引`() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("总额\tzong e\t100\n".reader()))
        }
        val result = OfflineT9Candidates.select("9664337",
            listOf("总额而", "总额", "用"), listOf("zong'e'er", "zong'e", "yong"))
        // 故意移除“用得上”等固定整词依据，体现新回退通路仍可能输出不自然句。
        assertEquals(listOf("总额而", "总额", "用"), result.firstPage.map { it.text })
        assertEquals(listOf(0), result.appendNativePage(listOf("总额而"), "9664337", listOf("zong'e'er")))
        assertEquals(listOf(0), result.appendNativePage(listOf("用"), "9664337", listOf("yong")))
        assertEquals(3, result.at(result.firstPage.size)?.nativeIndex)
        assertEquals(4, result.at(result.firstPage.size + 1)?.nativeIndex)
    }

    @Test fun `无固定词库时原生完整解码首屏和后页都可见`() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("".reader()))
        }
        val result = OfflineT9Candidates.select("9664337", listOf("总额而"), listOf("zong'e'er"))
        assertEquals(listOf("总额而"), result.firstPage.map { it.text })
        assertEquals(listOf(0), result.appendNativePage(listOf("总额而"), "9664337", listOf("zong'e'er")))
        assertEquals(listOf(0), result.appendNativePage(listOf("用"), "9664337", listOf("yong")))
        assertEquals(0, result.at(0)?.nativeIndex)
        assertEquals(1, result.at(1)?.nativeIndex)
        assertEquals(2, result.at(2)?.nativeIndex)
    }

    @Test fun `明确选择过的词库外词可用但仍必须符合当前读音`() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("".reader()))
        }
        val code = "26826226"
        val words = listOf("阿暖宝", "啊乱包")
        val readings = listOf("a'nuan'bao", "a'luan'bao")
        assertEquals(listOf("阿暖宝"), OfflineT9Candidates.select(code, words, readings).firstPage.map { it.text })
        OfflineT9Candidates.learn(code, "阿暖宝")
        OfflineT9Candidates.learn(code, "啊乱包")
        closeStore()
        OfflineT9Candidates.init(context)
        assertEquals(listOf("阿暖宝"), OfflineT9Candidates.select(code, words, readings).firstPage.map { it.text })
    }

    @Test fun `四五键低频完整词不能强抢原生常用候选`() {
        OfflineT9Candidates::class.java.getDeclaredField("lexicon").apply { isAccessible = true }
            .set(OfflineT9Candidates, T9Lexicon.parse("你好\tni hao\t725\n泥蒿\tni hao\t1\n".reader()))
        OfflineT9Candidates::class.java.getDeclaredField("domains").apply { isAccessible = true }
            .set(OfflineT9Candidates, T9Lexicon.parse("".reader()))
        assertEquals("你好", OfflineT9Candidates.select("64426", listOf("你好"), listOf("ni'hao")).firstPage.first().text)
        val withNative = OfflineT9Candidates.select("64426", listOf("你好", "泥蒿"), listOf("ni'hao", "ni'hao"))
        assertEquals("你好", withNative.firstPage.first().text)
        assertEquals(0, withNative.firstPage.first().nativeIndex)
    }

    @Test fun `第九条已收录原生首选不因词典首屏截断而被降为拼接词`() {
        val words = (0..8).joinToString("\n") { "测试${'甲' + it}\tchong dian bao\t${2000 - it}" }
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse(words.reader()))
        }
        val text = "测试${'甲' + 8}"
        val selection = OfflineT9Candidates.select("2466434262", listOf(text), listOf("chong'dian'bao"))
        assertEquals(text, selection.firstPage.first().text)
        assertEquals(0, selection.firstPage.first().nativeIndex)
    }

    @Test fun `同文字的短读音不能借另一读音继承内部前缀历史`() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("甲乙丙\tni fa huo\t100\n丁戊己\tni fa huo\t1000\n".reader()))
        }
        repeat(3) { OfflineT9Candidates.learn("6432", "甲乙丙") }
        val result = OfflineT9Candidates.select("6432486", listOf("甲乙丙"), listOf("ni'fa"))
        assertEquals("丁戊己", result.firstPage.first().text)
    }

    @Test fun `末字补全的怎么优先于低位公开词夜魔`() {
        val selection = OfflineT9Candidates.select("9366", listOf("夜魔", "怎么"), listOf("ye'mo", "zen'me"))
        assertEquals("怎么", selection.firstPage.first().text)
        assertEquals(1, selection.firstPage.first().nativeIndex)
        assertTrue(selection.firstPage.indexOfFirst { it.text == "夜魔" } > 0) // 用户已允许真实低频词低位出现。
        assertEquals("怎么", OfflineT9Candidates.select("9366", listOf("夜魔"), listOf("ye'mo")).firstPage.first().text)
    }

    @Test fun `补完整后选怎么的习惯回到短码且重启保留但不重复写入`() {
        // 都是已收录词，夜魔为完整匹配默认；隔离通用基础排序，证明是学习起效。
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("怎么\tzen me\t100\n夜魔\tye mo\t200\n".reader()))
        }
        val native = listOf("夜魔")
        val comments = listOf("ye'mo")
        assertEquals("夜魔", OfflineT9Candidates.select("9366", native, comments).firstPage.first().text)
        OfflineT9Candidates.learn("93663", "怎么")
        assertEquals("夜魔", OfflineT9Candidates.select("9366", native, comments).firstPage.first().text)
        repeat(2) { OfflineT9Candidates.learn("93663", "怎么") }
        closeStore()
        OfflineT9Candidates.init(context)
        repeat(2) { assertEquals("怎么", OfflineT9Candidates.select("9366", native, comments).firstPage.first().text) }
        val db = LocalInputStore(context)
        try {
            assertTrue(db.learned("9366").isEmpty())
            assertEquals(3L, db.learned("93663").single().count)
        } finally { db.close() }
        // 内部简拼及不属于末字补全的编码不能继承完整词的权重。
        assertFalse(OfflineT9Candidates.select("963", emptyList(), emptyList()).firstPage.any { it.text == "怎么" })
    }

    @Test fun `短码选择影响合法三键与完整码但不跨入字母输入`() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("需求\txu qiu\t1\n许求\txu qiu\t1000\n".reader()))
        }
        val native = listOf("许求")
        val comments = listOf("xu'qiu")
        assertEquals("许求", OfflineT9Candidates.select("98748", native, comments).firstPage.first().text)
        repeat(3) { OfflineT9Candidates.learn("9874", "需求") }
        assertEquals("需求", OfflineT9Candidates.select("98748", native, comments).firstPage.first().text)
        assertEquals("需求", OfflineT9Candidates.select("987", native, comments).firstPage.first().text)
        assertEquals("许求", OfflineT9Candidates.select("xuqiu", native, comments).firstPage.first().text)
    }

    @Test fun `前缀相似但不符读音的旧历史不能迁入短码`() {
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("怎么\tzen me\t100\n夜魔\tye mo\t200\n".reader()))
        }
        repeat(10) { OfflineT9Candidates.learn("936632", "怎么") }
        assertEquals("夜魔", OfflineT9Candidates.select("9366", listOf("夜魔"), listOf("ye'mo")).firstPage.first().text)
    }

    @Test fun `你发货吧只补末字且不匹配的灭除妈啊不能显示`() {
        for (code in listOf("64324862", "643248622")) {
            val selection = OfflineT9Candidates.select(code, listOf("灭除妈啊"), listOf("mie'chu'ma'a"))
            assertEquals(code, "你发货吧", selection.firstPage.first().text)
            if (code == "64324862") assertFalse(selection.firstPage.any { it.text == "灭除妈啊" })
        }
        assertFalse(OfflineT9Candidates.select("6342", emptyList(), emptyList()).firstPage.any { it.text == "你发货吧" })
    }

    @Test fun `充电宝末字简拼连续输入都可选且三次选择后重启保持首位`() {
        val native = listOf("冲屌啊")
        val comments = listOf("chong'diao'a")
        for (code in listOf("2466434262", "24664342622", "246643426226")) {
            assertTrue(code, OfflineT9Candidates.select(code, native, comments).firstPage.any { it.text == "充电宝" })
        }
        repeat(3) { OfflineT9Candidates.learn("2466434262", "充电宝") }
        closeStore()
        OfflineT9Candidates.init(context)
        assertEquals("充电宝", OfflineT9Candidates.select("2466434262", native, comments).firstPage.first().text)
    }

    @Test fun `首屏三十二条之外的合法学习词仍可召回并在重启后成为首选`() {
        val words = (0..35).joinToString("\n") { "测试${'甲' + it}\tchong dian bao\t${1000 - it}" } + "\n充电\tchong dian\t100"
        OfflineT9Candidates::class.java.getDeclaredField("lexicon").apply { isAccessible = true }
            .set(OfflineT9Candidates, T9Lexicon.parse(words.reader()))
        OfflineT9Candidates::class.java.getDeclaredField("domains").apply { isAccessible = true }
            .set(OfflineT9Candidates, T9Lexicon.parse("".reader()))
        val learnedText = "测试${'甲' + 35}"
        val code = "2466434262"
        assertFalse(OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage.any { it.text == learnedText })
        repeat(3) { OfflineT9Candidates.learn(code, learnedText) }
        closeStore()
        OfflineT9Candidates.init(context)
        val result = OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage
        assertEquals(learnedText, result.first().text)
        assertEquals("chong dian bao", result.first().pinyin)
        assertNull(result.first().nativeIndex)
        val withNative = OfflineT9Candidates.select(code,
            listOf("冲屌啊", learnedText), listOf("chong'diao'a", "chong'dian'bao"))
        assertEquals(learnedText, withNative.firstPage.first().text)
        assertEquals(1, withNative.firstPage.first().nativeIndex)
        assertEquals(listOf(0), withNative.appendNativePage(listOf("充电"), code, listOf("chong'dian")))
        assertEquals(2, withNative.at(withNative.firstPage.size)?.nativeIndex)
    }

    @Test fun `字母输入不使用原生注释覆盖预编辑或限制历史`() {
        // 隔离本地词库读音，验证原生注释自身不会被用于字母预编辑。
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("".reader()))
        }
        val selection = OfflineT9Candidates.select("bugaox", listOf("不高兴"), listOf("bu'gao'xing"))
        assertEquals("", selection.firstPage.first { it.nativeIndex == 0 }.pinyin)
        assertEquals(listOf(0), selection.appendNativePage(listOf("遍天下"), "bugaox", listOf("bian'tian'xia")))
    }

    @Test fun `原生同词不同读音逐项校验且超出八条仍受保护`() {
        val words = List(12) { "遍天下" } + listOf("不高兴", "不高兴")
        val comments = List(12) { "bian'tian'xia" } + listOf("bu'gao'xing", "bian'tian'xia")
        val selection = OfflineT9Candidates.select("284269", words, comments)
        assertFalse(selection.firstPage.any { it.text == "遍天下" })
        assertEquals(12, selection.firstPage.first { it.text == "不高兴" }.nativeIndex)
        assertEquals(listOf(12), selection.appendNativePage(words, "284269", comments))
    }

    @Test fun `原生候选按自身拼音校验而非本地词库排除集`() {
        val words = listOf("遍天下", "被调整", "蹦跳着", "便条纸", "不高兴")
        val readings = listOf("bian'tian'xia", "bei'tiao'zheng", "beng'tiao'zhe", "bian'tiao'zhi", "bu'gao'xing")
        repeat(5) { OfflineT9Candidates.learn("284269", "遍天下") }
        // 有明确选择依据也不能绕过拼音；清空词库避免资产碰巧覆盖测试。
        OfflineT9Candidates.learn("284269", "不高兴")
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, T9Lexicon.parse("".reader()))
        }
        val selection = OfflineT9Candidates.select("284269", words, readings)
        assertEquals(listOf("不高兴"), selection.firstPage.map { it.text })
        assertEquals(4, selection.firstPage.first().nativeIndex)
        val visible = selection.appendNativePage(words, "284269", readings)
        assertEquals(listOf(4), visible)
        assertEquals(9, selection.at(1)?.nativeIndex)
    }

    @Test fun `翻页继续排除未输入的内部音节且保留原生选择索引`() {
        val selection = OfflineT9Candidates.select("284269", listOf("不高兴"))
        val size = selection.firstPage.size
        assertEquals(listOf(1), selection.appendNativePage(listOf("长条形", "不高兴"), "284269"))
        assertEquals(2, selection.at(size)?.nativeIndex)
    }

    @Test fun `不高兴编码排除内部简拼及旧学习词且补全不抢原生首选`() {
        repeat(5) { OfflineT9Candidates.learn("284269", "长条形") }
        val results = OfflineT9Candidates.query("284269", listOf("长条形", "不高兴"))
        assertEquals("不高兴", results.first().text)
        assertEquals(1, results.first().nativeIndex)
        assertFalse(results.any { it.text == "长条形" })
    }

    @Test fun `实际词库和学习记录都不能将六字纯简拼重新加入候选`() {
        repeat(5) { OfflineT9Candidates.learn("649439", "美国最高法院") }
        val results = OfflineT9Candidates.query("649439", listOf("美国最高法院", "你这样"))
        assertEquals("你这样", results.first().text)
        assertEquals(1, results.first().nativeIndex)
        assertFalse(results.any { it.text == "美国最高法院" })
    }

    @Test fun `全键选词次数积累后本地排名提升且重开数据库保留`() {
        val native = listOf("续期", "需求")
        assertEquals("续期", OfflineT9Candidates.query("xuq", native).first().text)
        OfflineT9Candidates.learn("xuq", "需求")
        assertEquals("续期", OfflineT9Candidates.query("xuq", native).first().text)
        repeat(2) { OfflineT9Candidates.learn("xuq", "需求") }
        closeStore()
        OfflineT9Candidates.init(context)
        val first = OfflineT9Candidates.query("xuq", native).first()
        assertEquals("需求", first.text)
        assertEquals(1, first.nativeIndex)
        assertEquals(1, OfflineT9Candidates.query("xuq", native).count { it.text == "需求" })
    }
    @Test fun `九宫格短码常用词优先且一次明确改选就生效`() {
        val native = listOf("续期", "需求")
        val before = OfflineT9Candidates.query("987", native).map { it.text }
        assertTrue(before.containsAll(listOf("需求", "续期")))
        assertTrue(before.indexOf("需求") < before.indexOf("续期"))
        OfflineT9Candidates.learn("987", "续期")
        assertEquals("续期", OfflineT9Candidates.query("987", native).first().text)
        assertEquals("续期", OfflineT9Candidates.query("xuq", native).first().text)
        assertEquals("后", OfflineT9Candidates.query("468", listOf("后", "候")).first().text)
    }
    @Test fun `真实离线词典合法末字补全优于未收录拼接且领域词实际加载`() {
        assertTrue(OfflineT9Candidates.query("46898262", listOf("后远啊")).any { it.text == "候选词" })
        assertEquals("候选词", OfflineT9Candidates.query("46898262", listOf("后远啊")).first().text)
        assertTrue(OfflineT9Candidates.query("xuqiupingshen").any { it.text == "需求评审" })
    }
}
