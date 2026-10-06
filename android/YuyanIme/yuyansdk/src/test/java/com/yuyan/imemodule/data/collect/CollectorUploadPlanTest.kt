package com.yuyan.imemodule.data.collect
import org.junit.Assert.*
import org.junit.Test

class CollectorUploadPlanTest {
    @Test fun cellularTextAndLocationNeverWaitForOrdinaryFifteenMinuteBatch() {
        var now=0L;val plan=CollectorUploadPlan { now }
        val kinds=setOf("location","chat_messages","chat_asset","phrase_use")
        val first=plan.select(false,true,kinds,true,false)
        assertTrue(first.events && first.location && first.chatText);assertFalse(first.images)
        now=299_999;val before=plan.select(false,true,kinds,true,false)
        assertFalse(before.events || before.location || before.chatText)
        now=300_000;val five=plan.select(false,true,kinds,true,false)
        assertFalse(five.events || five.regular);assertTrue(five.location && five.chatText)
        now=900_000;assertTrue(plan.select(false,true,kinds,true,false).events)
    }
    @Test fun emptyQueueAndTypingDoNotConsumeFutureTextOrLocationSlots() {
        var now=0L;val plan=CollectorUploadPlan { now }
        plan.select(false,true,emptySet(),false,false)
        now=10_000
        val pending=setOf("chat_asset","chat_messages","location")
        val typing=plan.select(true,false,pending,true,true)
        assertTrue(typing.images);assertFalse(typing.events || typing.location || typing.chatText)
        val idle=plan.select(false,true,pending,true,false)
        assertTrue(idle.events && idle.location && idle.chatText);assertFalse(idle.images)
    }
    @Test fun wifiOrdinaryAndLocationHaveIndependentMinuteAndFiveMinuteIntervals() {
        var now=0L;val plan=CollectorUploadPlan { now };val kinds=setOf("location")
        plan.select(true,true,kinds,true,false)
        now=60_000;val next=plan.select(true,true,kinds,true,false)
        assertTrue(next.events);assertFalse(next.location)
        now=300_000;assertTrue(plan.select(true,true,kinds,true,false).location)
    }
}
