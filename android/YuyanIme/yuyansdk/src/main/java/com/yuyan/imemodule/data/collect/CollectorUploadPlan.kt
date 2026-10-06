package com.yuyan.imemodule.data.collect

/** 只有存在待办才消费对应批次资格，快类别不带动慢类别一起联网。 */
internal class CollectorUploadPlan(now: () -> Long) {
    private val regular=CollectorSyncCadence(now=now)
    private val location=CollectorSyncCadence(300_000L,300_000L,now)
    private val text=CollectorSyncCadence(5_000L,300_000L,now)
    fun select(wifi: Boolean, idle: Boolean, kinds: Set<String>, events: Boolean, imagesAllowed: Boolean): DeliverySelection {
        val normalPending=events || kinds.any { it !in setOf("location","chat_asset","chat_messages") }
        val normal=idle && normalPending && regular.tryStart(wifi)
        return DeliverySelection(normal,normal,
            idle && "location" in kinds && location.tryStart(wifi),
            idle && "chat_messages" in kinds && text.tryStart(wifi),
            "chat_asset" in kinds && imagesAllowed)
    }
}
