package com.yuyan.imemodule.data.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationPageTest {
    private fun labels(vararg text: String) = text.map { NavigationLabel(null, it, null) }
    @Test fun bothMapsRecognizeLabeledOverview() {
        for (pkg in listOf("com.autonavi.minimap", "com.baidu.BaiduMap")) {
            val page = NavigationPage.parse(pkg, labels("起点：我的位置", "终点：杭州东站", "35分钟", "12公里", "开始导航"))
            assertTrue(page is NavigationPage.Overview)
            assertEquals("杭州东站", (page as NavigationPage.Overview).route.destination)
        }
    }
    @Test fun searchHomeUnsupportedAndSimulationAreNotOverviewOrActive() {
        for (text in listOf(labels("杭州东站", "开始导航"), labels("起点：我的位置", "终点：杭州东站", "模拟导航", "35分钟"),
            labels("退出模拟导航", "剩余12公里", "全览"))) {
            assertEquals(NavigationPage.Other, NavigationPage.parse("com.autonavi.minimap", text))
        }
        assertEquals(NavigationPage.Other, NavigationPage.parse("com.other.app", labels("退出导航", "剩余12公里")))
    }
    @Test fun activeRequiresNavigationEvidenceNotJustStartButton() {
        assertTrue(NavigationPage.parse("com.baidu.BaiduMap", labels("退出导航", "剩余12公里", "终点：杭州东站")) is NavigationPage.Active)
        assertEquals(NavigationPage.Other, NavigationPage.parse("com.autonavi.minimap", labels("退出导航")))
    }
    @Test fun endpointIdsAndSeparateLabelsAreSupportedWithoutUsingButtonAsPlace() {
        val page = NavigationPage.parse("com.autonavi.minimap", listOf(
            NavigationLabel("pkg:id/route_start_name", "我的位置", null), NavigationLabel("pkg:id/route_end_name", "杭州东站", null),
            NavigationLabel("pkg:id/start_navi_button", "开始导航", null)) + labels("35分钟", "12公里"))
        assertTrue(page is NavigationPage.Overview)
        val separate = NavigationPage.parse("com.baidu.BaiduMap", labels("起点", "家", "终点", "公司", "25分钟", "开始导航"))
        assertEquals("公司", (separate as NavigationPage.Overview).route.destination)
    }
}
