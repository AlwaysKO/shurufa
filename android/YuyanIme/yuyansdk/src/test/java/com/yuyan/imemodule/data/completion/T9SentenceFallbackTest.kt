package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.zip.GZIPInputStream

class T9SentenceFallbackTest {
    private fun fixture() = T9SentenceFallback.load("""
        你	ni	10000
        年	nian	9000
        好	hao	8000
        和	he	7000
        我	wo	6000
        要	yao	5500
        啊	a	5000
        的	de	4000
        就	jiu	3000
        是	shi	2000
        他	ta	1000
        你好	ni hao	9000
        我们	wo men	7000
    """.trimIndent().reader())
    private fun digits(value: String): String = value.filter { it in 'a'..'z' }.map {
        "22233344455566677778889999"[it - 'a']
    }.joinToString("")
    private fun validate(code: String, results: List<T9SentenceFallback.Sentence>) {
        assertTrue("multiple combinations", results.size in 2..4)
        assertEquals(results.size, results.map { it.text }.distinct().size)
        for (result in results) {
            assertEquals(code, digits(result.typedPreedit))
            assertEquals(result.text.length, result.pinyin.split(' ').size)
            assertEquals(result.text.length, result.typedPreedit.split("'").size)
            assertTrue(result.text.length <= 30)
            assertTrue(result.score.isFinite())
            result.pinyin.split(' ').zip(result.typedPreedit.split("'")).forEach { (full, typed) ->
                assertTrue("actual syllable prefix", full.startsWith(typed))
            }
        }
    }
    @Test fun `complete random path keeps every numeric key and multiple choices`() {
        validate("64426964", fixture().decode("64426964"))
    }
    @Test fun `internal initials and incomplete last syllable retain actual consumption`() {
        validate("9999999999", fixture().decode("9999999999"))
    }
    @Test fun `invalid and out of bounds input produce no result`() {
        for (code in listOf("", "2345678", "2".repeat(49), "23456780", "nihaonihao", "2345'6789")) {
            assertTrue(fixture().decode(code).isEmpty())
        }
    }
    @Test fun `invalid rows cannot create mismatched characters or invented readings`() {
        val decoder = T9SentenceFallback.load("""
            你好	ni	100
            你	nI	100
            你1	ni hao	100
            好	hao	-1
            我	wo	not-a-number
        """.trimIndent().reader())
        assertTrue(decoder.decode("64426964").isEmpty())
    }
    @Test fun `same code is deterministic and last incomplete syllable is represented exactly`() {
        val decoder = fixture()
        val code = "64426964"
        val first = decoder.decode(code)
        assertEquals(first, decoder.decode(code))
        assertTrue(first.any { result ->
            val full = result.pinyin.split(' ').last()
            val typed = result.typedPreedit.split("'").last()
            typed.length < full.length
        })
    }
    @Test fun `production asset random replay has a fixed expansion budget`() {
        val asset = java.io.File("src/main/assets/completion/sentence_fallback.tsv.gzip")
        assertTrue(asset.exists())
        assertTrue(asset.length() < 1_000_000)
        val decoder = GZIPInputStream(asset.inputStream()).reader().use(T9SentenceFallback::load)
        val random = Random(20261014)
        val codes = listOf("64364526949767646646976666") + List(160) {
            val length = 8 + random.nextInt(41)
            CharArray(length) { ('2'.code + random.nextInt(8)).toChar() }.concatToString()
        }
        var decoded = 0
        var maximumEdges = 0
        for (code in codes) {
            val result = decoder.decodeWithStats(code)
            maximumEdges = maxOf(maximumEdges, result.expandedEdges)
            assertTrue("bounded edges $code ${result.expandedEdges}", result.expandedEdges <= code.length * 4 * 72)
            // At most 30 Han characters; extremely long runs may have no complete path.
            if (result.candidates.isNotEmpty()) { validate(code, result.candidates); decoded++ }
        }
        println("FALLBACK_STRESS cases=${codes.size} decoded=$decoded maxEdges=$maximumEdges")
        validate(codes.first(), decoder.decode(codes.first()))
    }
}
