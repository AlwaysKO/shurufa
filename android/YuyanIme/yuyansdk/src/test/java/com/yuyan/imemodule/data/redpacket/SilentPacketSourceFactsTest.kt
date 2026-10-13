package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class SilentPacketSourceFactsTest {
    private fun line(text: String, top: Int = 100, left: Int = 310, right: Int = 690, bottom: Int = top + 60) =
        PacketVisualLine(text, IntRect(left, top, right, bottom))
    @Test fun titleCandidatesUseCurrentParserBoundsAndGroupSuffixRules() {
        val facts = silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(
            line("私密群(12)"), line("另一个群（2）", top = 170), line("非群(1)", top = 230),
            line("正文", top = 1000), line(" "), line("越界", right = 1001),
            line("太靠边", left = 20), line("太高", bottom = 350))))
        assertEquals(3, facts.titleCandidateCount)
        assertEquals(2, facts.groupSuffixCount)
        assertFalse(facts.toString().contains("私密群"))
    }
    @Test fun bottomTabsNeedThreeDistinctFixedLabelsInBottomArea() {
        val tabs = listOf("微信", "通讯录", "发现", "我").mapIndexed { index, text ->
            line(text, top = 1900, left = index * 200 + 20, right = index * 200 + 180)
        }
        assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, tabs.take(3))).bottomWechatTabsPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, tabs.take(2))).bottomWechatTabsPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(tabs[0], tabs[0], tabs[0]))).bottomWechatTabsPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, tabs.map { it.copy(bounds = IntRect(20, 1000, 180, 1060)) })).bottomWechatTabsPresent)
    }
    @Test fun regionCountsExcludeInvalidRectanglesWithoutKeepingPixelsOrText() {
        val facts = silentPacketSourceFacts(PacketVisualFrame(1000, 2200, emptyList(),
            orangeRegions = listOf(IntRect(150, 650, 760, 870), IntRect(-1, 0, 10, 20)),
            menuDots = listOf(IntRect(909, 122, 947, 128), IntRect(5, 5, 5, 5))))
        assertEquals(1, facts.orangeRegionCount)
        assertEquals(1, facts.menuDotCount)
        assertTrue(SilentPacketSourceFacts::class.java.declaredFields.all { it.type.isPrimitive })
    }
    @Test fun promptFactsUseOnlyExactFixedLabels() {
        for (label in listOf("登录", "注册", "用手机号登录", "使用其他方式登录"))
            assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(label)))).loginPromptPresent)
        for (label in listOf("正在加载", "请稍候", "加载中"))
            assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(label)))).loadingPromptPresent)
        for (label in listOf("网络连接不可用", "网络连接失败", "无法连接到服务器"))
            assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(label)))).networkErrorPresent)
        val facts = silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(
            line("今天登录了吗"), line("朋友说正在加载"), line("收到网络连接失败提示了吗"))))
        assertFalse(facts.loginPromptPresent)
        assertFalse(facts.loadingPromptPresent)
        assertFalse(facts.networkErrorPresent)
    }
    @Test fun securityCenterNeedsFixedDeviceLabelCombination() {
        assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200,
            listOf(line("微信安全中心"), line("当前设备", top = 1000)))).loginPromptPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line("当前设备")))).loginPromptPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line("微信安全中心")))).loginPromptPresent)
    }
    @Test fun permissionFactsRequireFullPromptOrAnAllowDenyCombination() {
        for (label in listOf("仅在使用该应用时允许", "隐私保护指引", "微信隐私保护指引"))
            assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(label)))).permissionPromptPresent)
        assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200,
            listOf(line("允许"), line("不允许", top = 1200)))).permissionPromptPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line("允许"), line("同意")))).permissionPromptPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200,
            listOf(line("这次不允许"), line("请看隐私保护指引说明")))).permissionPromptPresent)
    }
    @Test fun composerLabelsRequireBottomControlGeometry() {
        for (label in listOf("按住说话", "切换到按住说话")) {
            assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(label, top = 1900)))).chatComposerPresent)
            assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(label, top = 1000)))).chatComposerPresent)
        }
        assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200,
            listOf(line("发送", top = 1900, left = 850, right = 960)))).chatComposerPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200,
            listOf(line("发送", top = 1900, left = 120, right = 230)))).chatComposerPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200,
            listOf(line("请点击发送", top = 1900, left = 850, right = 960)))).chatComposerPresent)
    }
    @Test fun newFlagsDefaultToFalseForOldConstructorsAndInvalidFrames() {
        for (facts in listOf(SilentPacketSourceFacts(0, 0, 0, false, 0),
            silentPacketSourceFacts(PacketVisualFrame(0, 2200, listOf(line("登录")))))) {
            assertFalse(facts.loginPromptPresent)
            assertFalse(facts.loadingPromptPresent)
            assertFalse(facts.networkErrorPresent)
            assertFalse(facts.permissionPromptPresent)
            assertFalse(facts.chatComposerPresent)
        }
    }
    @Test fun recognizedLineCountIncludesOnlyValidNonblankLinesWithoutRetainingText() {
        val facts = silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(
            line("状态栏", top = 0), line("不能输出的正文", top = 1000), line("底部", top = 1900),
            line(" "), line("越界", right = 1001), line("零面积", left = 20, right = 20))))
        assertEquals(3, facts.recognizedLineCount)
        assertEquals(1, facts.contentLineCount)
        assertFalse(facts.toString().contains("不能输出的正文"))
        val defaults = SilentPacketSourceFacts(0, 0, 0, false, 0)
        assertEquals(0, defaults.recognizedLineCount)
        assertEquals(0, defaults.contentLineCount)
        assertFalse(defaults.wechatTitleLabelPresent)
    }
    @Test fun contentCountUsesInclusiveMiddleAreaCenters() {
        val facts = silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(
            line("top-edge", top = 322), line("bottom-edge", top = 1730),
            line("above", top = 321), line("below", top = 1731))))
        assertEquals(4, facts.recognizedLineCount)
        assertEquals(2, facts.contentLineCount)
    }
    @Test fun wechatLabelMustBeExactTopTitleAndNewEvidenceRendersFixedKeys() {
        for (label in listOf("微信", "微 信", "微信(12)", "微信（3）"))
            assertTrue(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(label)))).wechatTitleLabelPresent)
        for (label in listOf("微信学习群(12)", "来自微信", "微信(未读)"))
            assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(label)))).wechatTitleLabelPresent)
        assertFalse(silentPacketSourceFacts(PacketVisualFrame(1000, 2200,
            listOf(line("微信", top = 1000)))).wechatTitleLabelPresent)
        val source = SilentPacketSourceFacts(0, 0, 0, false, 0,
            recognizedLineCount = 3, contentLineCount = 1, wechatTitleLabelPresent = true)
        val diagnostic = SilentPacketReadDiagnostic(SilentPacketReadResult.VALID, 0, 0,
            SilentPacketPageFacts(false, false, false, false, false, 0), source)
        assertTrue(diagnostic.renderFacts().contains("recognizedLineCount=3"))
        assertTrue(diagnostic.renderFacts().contains("contentLineCount=1"))
        assertTrue(diagnostic.renderFacts().contains("wechatTitleLabelPresent=true"))
    }
    @Test fun longErrorSentenceSetsOnlyFixedKeywordBitsWithoutBeingPageQualification() {
        val facts = silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(
            "当前设备环境不支持分屏多开，无法登录，请退出后重新验证手机网络安全风险，电脑模拟器主屏副屏", top = 1000))))
        val expected = SilentPacketFixedKeyword.entries.fold(0L) { mask, keyword -> mask or keyword.bit }
        assertEquals(expected, facts.fixedKeywordMask)
        assertFalse(facts.loginPromptPresent)
        assertFalse(facts.permissionPromptPresent)
        assertFalse(facts.chatComposerPresent)
    }
    @Test fun keywordMetadataAndRenderingNeverRetainPrivateOcrSentence() {
        val privateText = "联系人王某说手机验证码为987654，请不要转发"
        val facts = silentPacketSourceFacts(PacketVisualFrame(1000, 2200, listOf(line(privateText, top = 1000))))
        assertEquals(SilentPacketFixedKeyword.PHONE.bit or SilentPacketFixedKeyword.VERIFICATION.bit,
            facts.fixedKeywordMask)
        assertEquals(listOf("PHONE", "VERIFICATION"), facts.fixedKeywordNames())
        val diagnostic = SilentPacketReadDiagnostic(SilentPacketReadResult.VALID, 0, 0,
            SilentPacketPageFacts(false, false, false, false, false, 0), facts)
        val rendered = diagnostic.renderFacts()
        assertTrue(rendered.contains("fixedKeywords=[PHONE, VERIFICATION]"))
        for (privatePart in listOf(privateText, "王某", "987654", "手机", "验证")) {
            assertFalse(rendered.contains(privatePart))
            assertFalse(facts.toString().contains(privatePart))
        }
    }
    @Test fun invalidFramesAndOldConstructorsHaveZeroMaskAndUnknownBitsAreNotRendered() {
        for (facts in listOf(SilentPacketSourceFacts(0, 0, 0, false, 0),
            silentPacketSourceFacts(PacketVisualFrame(0, 2200, listOf(line("不支持设备登录")))))) {
            assertEquals(0L, facts.fixedKeywordMask)
            assertTrue(facts.fixedKeywordNames().isEmpty())
        }
        val unknown = SilentPacketSourceFacts(0, 0, 0, false, 0,
            fixedKeywordMask = Long.MIN_VALUE or SilentPacketFixedKeyword.NETWORK.bit)
        assertEquals(listOf("NETWORK"), unknown.fixedKeywordNames())
    }
    @Test fun invalidFrameHasNoSourceEvidenceAndOldSnapshotDefaultsToNone() {
        assertEquals(SilentPacketSourceFacts(0, 0, 0, false, 0),
            silentPacketSourceFacts(PacketVisualFrame(0, 2200, listOf(line("private")))))
        val snapshot = PacketVisualSnapshot(PacketVisualMatch(PacketPage(), emptyMap()), 7,
            IntRect(0, 0, 100, 200), 0, 0, 1000)
        assertNull(snapshot.sourceFacts)
    }
}
