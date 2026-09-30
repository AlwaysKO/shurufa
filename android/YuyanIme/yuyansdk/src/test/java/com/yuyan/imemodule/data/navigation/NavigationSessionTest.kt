package com.yuyan.imemodule.data.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationSessionTest {
    private val route = NavigationRoute("amap", "我的位置", "杭州东站")
    private val image = byteArrayOf(1, 2, 3)
    @Test fun queryAloneAndActiveWithoutEvidenceAreNotRecorded() {
        val state = NavigationSession()
        state.preview(route, image, 1_000)
        assertNull(state.confirm("amap", null, 2_000))
        state.startClicked("amap", 2_000)
        val record = state.confirm("amap", null, 3_000)!!
        assertEquals("杭州东站", record.route.destination)
        assertArrayEquals(image, record.image)
        assertEquals(3_000L, record.startedAt)
    }
    @Test fun successDeduplicatesAndPersistenceFailureCanRetrySameId() {
        val state = NavigationSession()
        state.preview(route, image, 1_000); state.startClicked("amap", 2_000)
        val first = state.confirm("amap", null, 3_000)!!
        assertEquals(first.id, state.confirm("amap", null, 4_000)!!.id)
        state.persisted(first.id)
        assertNull(state.confirm("amap", null, 5_000))
        state.preview(route, image, 6_000); state.startClicked("amap", 7_000)
        assertNotEquals(first.id, state.confirm("amap", null, 8_000)!!.id)
    }
    @Test fun differentAppDestinationExpiryAndAbandonedQueryNeverConfirm() {
        for (mode in 0..4) {
            val state = NavigationSession()
            state.preview(route, image, 1_000); state.startClicked("amap", 2_000)
            if (mode == 3) state.abandon()
            if (mode == 4) state.preview(route.copy(destination = "西湖"), image, 2_500)
            assertNull(state.confirm(if (mode == 0) "baidu" else "amap", if (mode == 1) "西湖" else null,
                if (mode == 2) 400_000 else 3_000))
        }
    }
    @Test fun matchingDestinationCanConfirmWithoutClickButCannotConfirmDifferentRoute() {
        val state = NavigationSession()
        state.preview(route, image, 1_000)
        assertNull(state.confirm("amap", "西湖", 2_000))
        assertNotNull(state.confirm("amap", "杭州东站", 3_000))
    }
    @Test fun lateClicksAndClockRollbackDoNotConfirm() {
        val state = NavigationSession()
        state.preview(route, image, 100_000); state.startClicked("amap", 101_000)
        assertNull(state.confirm("amap", null, 90_000))
        assertNull(state.confirm("amap", null, 130_000))
    }
    @Test fun changedRouteInvalidatesOldImageEvenWhenNewScreenshotFails() {
        val state = NavigationSession()
        state.preview(route, image, 1_000)
        state.overviewChanged()
        state.startClicked("amap", 2_000)
        assertNull(state.confirm("amap", null, 3_000))
    }
    @Test fun freshOverviewFromStartClickKeepsClickEvidenceWhenLabelsChanged() {
        val state = NavigationSession()
        state.preview(route, image, 1_000)
        state.startClicked("amap", 2_000)
        val previousClick = state.overviewChanged(route)
        assertEquals(2_000L, previousClick)
        state.preview(route, byteArrayOf(4, 5, 6), 2_500, startClickAt = previousClick)
        val confirmed = state.confirm("amap", null, 3_000)!!
        assertArrayEquals(byteArrayOf(4, 5, 6), confirmed.image)
    }
}
