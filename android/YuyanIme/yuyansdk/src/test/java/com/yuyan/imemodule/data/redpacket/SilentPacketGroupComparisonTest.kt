package com.yuyan.imemodule.data.redpacket

import org.junit.Assert.*
import org.junit.Test

class SilentPacketGroupComparisonTest {
    @Test fun exactComparisonContainsOnlyPrimitiveFacts() {
        val facts = silentPacketGroupComparison("私密测试群", "私密测试群")
        assertTrue(facts.observedPresent); assertTrue(facts.exactEqual)
        assertTrue(facts.whitespaceEqual); assertTrue(facts.normalizedEqual)
        assertEquals(5, facts.expectedCharacterCount); assertEquals(5, facts.observedCharacterCount)
        assertFalse(facts.toString().contains("私密"))
        assertTrue(SilentPacketGroupComparison::class.java.declaredFields.all { it.type.isPrimitive })
    }
    @Test fun whitespaceAndWidthDifferencesAreDiagnosticOnly() {
        val spaces = silentPacketGroupComparison("测试群A", " 测试 群A ")
        assertFalse(spaces.exactEqual); assertTrue(spaces.whitespaceEqual); assertTrue(spaces.normalizedEqual)
        val width = silentPacketGroupComparison("测试群A", "测试群Ａ")
        assertFalse(width.exactEqual); assertFalse(width.whitespaceEqual); assertTrue(width.normalizedEqual)
    }
    @Test fun missingAndDifferentNamesDoNotBecomeNormalizationMatches() {
        val absent = silentPacketGroupComparison("测试群", null)
        assertFalse(absent.observedPresent); assertEquals(3, absent.expectedCharacterCount)
        assertEquals(0, absent.observedCharacterCount); assertFalse(absent.normalizedEqual)
        val other = silentPacketGroupComparison("测试群", "其他群")
        assertTrue(other.observedPresent); assertFalse(other.exactEqual)
        assertFalse(other.whitespaceEqual); assertFalse(other.normalizedEqual)
    }
    @Test fun charactersAreCountedWithoutSplittingSupplementaryUnicode() {
        val facts = silentPacketGroupComparison("群😀", "群😃")
        assertEquals(2, facts.expectedCharacterCount); assertEquals(2, facts.observedCharacterCount)
        assertFalse(facts.exactEqual); assertFalse(facts.normalizedEqual)
    }
    @Test fun renderingNeverIncludesOriginalName() {
        val facts = silentPacketGroupComparison("secret expected group", "private observed group")
        val diagnostic = SilentPacketReadDiagnostic(SilentPacketReadResult.VALID, 1, 2,
            SilentPacketPageFacts(true, true, false, false, false, 0), groupComparison = facts)
        val rendered = diagnostic.renderFacts()
        assertTrue(rendered.contains("groupExactEqual=false"))
        assertTrue(rendered.contains("groupNormalizedEqual=false"))
        assertFalse(rendered.contains("secret")); assertFalse(rendered.contains("private"))
    }
}
