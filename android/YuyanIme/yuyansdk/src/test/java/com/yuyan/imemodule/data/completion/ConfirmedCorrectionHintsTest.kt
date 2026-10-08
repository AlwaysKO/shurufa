package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class ConfirmedCorrectionHintsTest {
    @Test fun `严格提示只作用精确编码词语且重放不续期`() {
        val hints = ConfirmedCorrectionHints()
        hints.record("a", "3264542", "放假", 100)
        hints.record("a", "3264542", "放假", 200)
        assertEquals(100L, hints.at("3264542", "放假", 300))
        assertNull(hints.at("326", "放假", 300))
        assertNull(hints.at("3264542", "房价", 300))
        hints.cancel("a")
        assertNull(hints.at("3264542", "放假", 300))
    }
    @Test fun `提示有容量与时间边界且不接受未来时间`() {
        val hints = ConfirmedCorrectionHints()
        repeat(129) { hints.record("$it", "78", "$it", 100) }
        assertNull(hints.at("78", "0", 100))
        assertEquals(100L, hints.at("78", "128", 100))
        assertNull(hints.at("78", "128", 99))
        assertNull(hints.at("78", "128", 100 + PersonalCandidateRanker.RECENT_CHOICE_MS + 1))
    }
    @Test fun `明确纠错奖励独立有界且衰减不压倒大量历史`() {
        val now = 10_000_000L
        val ordinary = PersonalCandidateRanker.score(1, 1.0, now, now, now)
        val corrected = PersonalCandidateRanker.score(1, 1.0, now, now, now, now)
        assertEquals(2.0, corrected - ordinary, 0.0001)
        assertTrue(corrected > PersonalCandidateRanker.score(0, 1.0, now, now, now))
        assertTrue(corrected < PersonalCandidateRanker.score(0, 40.0, now, now, now))
        assertEquals(1.0, PersonalCandidateRanker.correctionBonus(now - 30*60*1000, now), 0.0001)
        assertEquals(0.0, PersonalCandidateRanker.correctionBonus(now + 1, now), 0.0)
    }
}
