package com.yuyan.imemodule.data.capture.page

internal fun acceptOrdinaryPageKind(kind: PageKind?): Boolean = kind !in setOf(PageKind.MEDIA_FEED, PageKind.PAYMENT, PageKind.VIDEO_CALL)

/** 未知页只能探测获准类别；独立预留，不能借视频额度保存支付，也不能反过来。 */
internal fun reservePriorityPageKinds(
    decision: PageDecision,
    reservePayment: () -> Boolean,
    reserveVideo: () -> Boolean,
): Set<PageKind> = buildSet {
    val unknown = decision.kind == null && decision.reason == "insufficient_evidence"
    if ((decision.kind == PageKind.PAYMENT || unknown) && reservePayment()) add(PageKind.PAYMENT)
    if ((decision.kind == PageKind.MEDIA_FEED || unknown) && reserveVideo()) add(PageKind.MEDIA_FEED)
}
