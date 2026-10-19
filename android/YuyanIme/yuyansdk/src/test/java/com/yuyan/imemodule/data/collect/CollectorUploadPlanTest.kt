package com.yuyan.imemodule.data.collect
import org.junit.Assert.*
import org.junit.Test

class CollectorUploadPlanTest {
    @Test fun screenshotConfirmationIsIndependentOfOrdinaryTextCadence() {
        var now = 0L
        val plan = CollectorUploadPlan { now }
        assertTrue(plan.select(true, true, setOf("chat_messages"), false, true).chatText)
        now = 1_000L
        val ready = plan.select(true, true, setOf("chat_image_messages", "chat_messages"), false, true)
        assertTrue(ready.images && ready.imageMessages)
        assertFalse(ready.chatText || ready.regular)
        assertFalse(plan.select(true, false, setOf("chat_image_messages"), false, true).imageMessages)
        assertFalse(plan.select(false, true, setOf("chat_image_messages"), false, false).images)
        assertNull(plan.nextUploadDelay(true, setOf("chat_image_messages"), false))
    }

    @Test fun pendingQueryDoesNotConsumeSlotsAndImagesNeverScheduleNonImageWake() {
        var now=0L; val plan=CollectorUploadPlan { now }
        assertNull(plan.nextUploadDelay(false,emptySet(),false))
        assertNull(plan.nextUploadDelay(true,setOf("chat_asset"),false))
        repeat(3) { assertEquals(5000L,plan.nextUploadDelay(false,setOf("location"),false)) }
        assertTrue(plan.select(false,true,setOf("location"),false,false).location)
        now=60_000
        assertEquals(240_000L,plan.nextUploadDelay(true,setOf("location"),false))
        // A newly pending category has not spent a slot just because locations were sent.
        assertEquals(5000L,plan.nextUploadDelay(false,setOf("location","chat_messages"),false))
        assertTrue(plan.select(true,true,setOf("chat_messages"),false,false).chatText)
        now=120_000
        assertEquals(180_000L,plan.nextUploadDelay(false,setOf("location","chat_messages"),false))
        assertEquals(5000L,plan.nextUploadDelay(false,emptySet(),true))
        assertTrue(plan.select(false,true,emptySet(),true,false).events)
    }
    @Test fun bothNetworksUseFiveMinuteBatchesForEveryNonImageKind() {
        for(wifi in listOf(false,true)) {
            var now=0L; val plan=CollectorUploadPlan { now }
            val kinds=setOf("location","chat_messages","chat_asset","phrase_use")
            val first=plan.select(wifi,true,kinds,true,false)
            assertTrue(first.events && first.regular && first.location && first.chatText)
            now=299_999
            val before=plan.select(wifi,true,kinds,true,false)
            assertFalse(before.events || before.regular || before.location || before.chatText)
            now=300_000
            val due=plan.select(wifi,true,kinds,true,false)
            assertTrue(due.events && due.regular && due.location && due.chatText)
            assertFalse(due.images)
        }
    }
    @Test fun emptyQueueAndTypingDoNotConsumeFutureSlots() {
        var now=0L; val plan=CollectorUploadPlan { now }
        plan.select(false,true,emptySet(),false,false)
        now=10_000
        val pending=setOf("chat_asset","chat_messages","location")
        val typing=plan.select(true,false,pending,true,true)
        assertTrue(typing.images); assertFalse(typing.events || typing.location || typing.chatText)
        val idle=plan.select(false,true,pending,true,false)
        assertTrue(idle.events && idle.regular && idle.location && idle.chatText); assertFalse(idle.images)
    }
}
