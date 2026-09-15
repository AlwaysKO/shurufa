package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class T9LexiconTest {
    private val lexicon = T9Lexicon.parse("候选词\thou xuan ci\t100\n后远啊\thou yuan a\t1\n输入法\tshu ru fa\t200\n你好\tni hao\t1000\n".reader())

    @Test fun `末音节每个前缀可匹配但不补写内部音节`() {
        val words = T9Lexicon.parse("充电宝\tchong dian bao\t100\n".reader())
        for (code in listOf("2466434262", "24664342622", "246643426226")) {
            assertEquals(code, listOf("充电宝"), words.query(code).map { it.text })
        }
        assertTrue(words.query("23426226").isEmpty())
    }

    @Test fun `前面的音节不能被首字母扩写为长条形`() {
        val words = T9Lexicon.parse("长条形\tchang tiao xing\t99999\n不高兴\tbu gao xing\t1\n".reader())
        assertEquals(listOf("不高兴"), words.query("284269").map { it.text })
        assertTrue(words.query("289").isEmpty())
        assertEquals("长条形", words.query(T9Lexicon.digits("changtiaoxing")).first().text)
    }

    @Test fun `纯简拼长词过滤四字边界不影响混拼短词及字母码`() {
        assertFalse(isT9CandidateAllowed("9475", "知识数量"))
        assertTrue(isT9CandidateAllowed("94475", "知识数量"))
        assertTrue(isT9CandidateAllowed("492", "候选词"))
        assertTrue(isT9CandidateAllowed("mgzgfy", "美国最高法院"))
        assertTrue(isT9CandidateAllowed("649439", "你这样"))
    }

    @Test fun `六位编码不召回纯首字母六字长词但保留三字混拼`() {
        val words = T9Lexicon.parse("美国最高法院\tmei guo zui gao fa yuan\t999999\n你这样\tni zhe yang\t100\n".reader())
        assertEquals(listOf("你这样"), words.query("649439").map { it.text })
        assertEquals("美国最高法院", words.query(T9Lexicon.digits("meiguozuig aofayuan".replace(" ", ""))).first().text)
    }

    @Test fun `全拼和末字简拼能匹配词条但不扩写前面的音节`() {
        for (code in listOf("468982624", "46898262")) {
            assertEquals(code, "候选词", lexicon.query(code).first().text)
        }
        assertEquals("输入法", lexicon.query("748783").first().text)
    }
    @Test fun `不把不匹配编码或带分词的输入强行解释为候选词`() {
        for (code in listOf("", "4", "468982620", "468'98262", "houxuan")) {
            assertTrue(code, lexicon.query(code).isEmpty())
        }
    }
    @Test fun `真实打包词库离线混拼能返回候选词`() {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        val asset = sequenceOf("src/main/assets", "yuyansdk/src/main/assets", "android/YuyanIme/yuyansdk/src/main/assets")
            .map { cwd.resolve("$it/completion/t9_lexicon.tsv.gzip") }.first { it.isFile }
        val actual = GZIPInputStream(asset.inputStream()).reader().use(T9Lexicon::parse)
        assertEquals("候选词", actual.query("46898262").first().text)
        assertEquals("候选词", actual.query("468982624").first().text)
    }
    @Test fun `短编码可以保留原生单字排序而不强推多字简拼`() {
        assertTrue(lexicon.query("468", allowAbbreviations = false).isEmpty())
        assertEquals("你好", lexicon.query("64426", allowAbbreviations = false).first().text)
    }
    @Test fun `拼音转换采用九宫格而不是首选词展示串`() {
        assertEquals("468982624", T9Lexicon.digits("houxuanci"))
        assertEquals("ni hao", lexicon.query("64426").first().pinyin)
    }

    @Test fun `中文全键拼音支持音节混拼`() {
        val words = T9Lexicon.parse("需求\txu qiu\t100\n续期\txu qi\t200\n".reader())
        assertEquals(setOf("需求", "续期"), words.queryPinyin("xuq").map { it.text }.toSet())
        assertEquals("需求", words.queryPinyin("xuqi u".replace(" ", "")).first().text)
        assertTrue(words.queryPinyin("xu9").isEmpty())
    }

    @Test fun `中文领域补充资产可离线召回`() {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        val asset = sequenceOf("src/main/assets", "yuyansdk/src/main/assets", "android/YuyanIme/yuyansdk/src/main/assets")
            .map { cwd.resolve("$it/completion/chinese_domains.tsv") }.first { it.isFile }
        val words = asset.reader().use(T9Lexicon::parse)
        for ((code, text) in listOf("xuqiupingshen" to "需求评审", "banbenhuitui" to "版本回退")) {
            assertEquals(text, words.queryPinyin(code).first().text)
        }
        assertEquals("周末愉快", words.queryPinyin("zhoumoyukuai").first().text)
    }
}
