package com.yuyan.imemodule.data.capture.adapter

import org.junit.Assert.*
import org.junit.Test

class ChatCapturePolicyTest {
    private val json = """{"schemaVersion":1,"revision":2,"rules":[{"id":"douyin-chat","packageName":"com.ss.android.ugc.aweme","minVersionCode":1,"maxVersionCode":9,"enabled":true,"titleIds":["vww"],"inputIds":["msg_et"],"bodyIds":["v6q"],"backLabels":["返回"],"settingsLabels":["更多"],"voiceLabels":["语音"],"voicePosition":"right"}]}"""
    @Test fun parsesStrictWireRulesAndMatchesVersion() {
        val p = ChatCapturePolicy.parse(json)!!
        assertEquals(2L, p.revision)
        assertEquals("right", p.rule("com.ss.android.ugc.aweme", 2)!!.voicePosition)
        assertNull(p.rule("com.ss.android.ugc.aweme", 10))
    }
    @Test fun rejectsMalformedUnsafeAndOversizedRules() {
        for (bad in listOf(json.replace("\"revision\":2", "\"revision\":9007199254740992"),
            json.replace("\"revision\":2", "\"revision\":2,\"extra\":true"),
            json.replace("\"语音\"", "\"*\""), json.replace("\"vww\"", "\"vww\",\"vww\""),
            json.replace("\"vww\"", "\"com.other:id/vww\""), json.replace("\"revision\":2", "\"revision\":2.1"),
            " ".repeat(32769) + json, "<html>not JSON</html>")) assertNull(bad.take(80), ChatCapturePolicy.parse(bad))
    }
    @Test fun cacheIsSourceScopedAndRejectsOldAndLateResponses() {
        val state = ChatCapturePolicyCache()
        state.switchSource("a")
        assertTrue(state.accept("a", json))
        assertFalse(state.accept("a", json.replace("\"revision\":2", "\"revision\":1")))
        state.switchSource("b")
        assertEquals(0L, state.current().revision)
        assertFalse(state.accept("a", json.replace("\"revision\":2", "\"revision\":3")))
        state.switchSource("a")
        assertEquals(2L, state.current().revision)
        assertFalse(state.accept("a", "bad"))
        assertEquals(2L, state.current().revision)
    }
    @Test fun safeIntegerUnicodeCountStrictRuleFieldsAndEmptyRules() {
        assertNotNull(ChatCapturePolicy.parse(json.replace("\"revision\":2", "\"revision\":9007199254740991")))
        assertNotNull(ChatCapturePolicy.parse(json.replace("\"revision\":2", "\"revision\":2e0")))
        assertNull(ChatCapturePolicy.parse(json.replace("\"voicePosition\":\"right\"", "\"voicePosition\":\"right\",\"extra\":true")))
        assertNotNull(ChatCapturePolicy.parse(json.replace("语音", "😀".repeat(64))))
        assertNull(ChatCapturePolicy.parse(json.replace("语音", "😀".repeat(65))))
        assertNull(ChatCapturePolicy.parse(json.replace("语音", "\ufeff语音")))
        assertNotNull(ChatCapturePolicy.parse("""{"schemaVersion":1,"revision":3,"rules":[]}"""))
        val state = ChatCapturePolicyCache()
        val epoch = state.switchSource("a")
        state.switchSource("b"); state.switchSource("a")
        assertFalse(state.accept("a", json, epoch))
    }
}
