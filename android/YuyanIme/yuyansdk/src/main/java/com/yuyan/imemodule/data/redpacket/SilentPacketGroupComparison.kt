package com.yuyan.imemodule.data.redpacket

import java.text.Normalizer

/** 仅诊断比较结果；不持有群名，也不授权点击。 */
internal data class SilentPacketGroupComparison(val observedPresent: Boolean = false,
    val expectedCharacterCount: Int = 0, val observedCharacterCount: Int = 0,
    val exactEqual: Boolean = false, val whitespaceEqual: Boolean = false, val normalizedEqual: Boolean = false)

internal fun silentPacketGroupComparison(expected: String, observed: String?): SilentPacketGroupComparison {
    fun compact(value: String) = value.filterNot { it.isWhitespace() }
    fun normalized(value: String) = compact(Normalizer.normalize(value, Normalizer.Form.NFKC))
    return SilentPacketGroupComparison(observed != null,
        expected.codePointCount(0, expected.length), observed?.codePointCount(0, observed.length) ?: 0,
        observed != null && expected == observed,
        observed != null && compact(expected) == compact(observed),
        observed != null && normalized(expected) == normalized(observed))
}
