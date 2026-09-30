package com.yuyan.imemodule.data.navigation

internal data class NavigationLabel(val viewId: String?, val text: String?, val description: String?)
internal sealed interface NavigationPage {
    data class Overview(val route: NavigationRoute) : NavigationPage
    data class Active(val platform: String, val destination: String?) : NavigationPage
    data object Other : NavigationPage
    companion object {
        fun platform(packageName: String): String? = when (packageName) {
            "com.autonavi.minimap" -> "amap"
            "com.baidu.BaiduMap" -> "baidu"
            else -> null
        }
        fun isStart(text: String) = text.trim() in setOf("开始导航", "开始步行导航", "开始骑行导航", "开始驾车导航")
        private val duration = Regex("\\d+\\s*(分钟|小时|公里|千米)")
        private val remaining = Regex("剩余.*\\d|\\d+\\s*(公里|千米|米|分钟).*后|到达时间|预计.*到达")
        private val startId = Regex("(^|_)(start|from|origin)(_|$)")
        private val endId = Regex("(^|_)(end|to|destination)(_|$)")
        private fun place(value: String): String? = value.trim().takeIf {
            it.isNotBlank() && it.length <= 300 && !it.contains('\n') &&
                it !in setOf("起点", "终点", "输入起点", "输入终点", "请输入起点", "请输入终点") &&
                !it.contains("导航") && !it.contains("选择")
        }
        fun parse(packageName: String, labels: List<NavigationLabel>): NavigationPage {
            val platform = platform(packageName) ?: return Other
            val words = labels.flatMap { listOfNotNull(it.text, it.description) }.map(String::trim).filter(String::isNotEmpty)
            if (words.any { it.contains("模拟导航") }) return Other
            fun endpoint(label: String, ids: Regex): String? {
                val pattern = Regex("^${label}[：:，,\\s]+(.+)$")
                words.forEach { word -> pattern.matchEntire(word)?.groupValues?.get(1)?.let(::place)?.let { return it } }
                labels.forEach { node ->
                    val id = node.viewId?.substringAfterLast('/')?.lowercase().orEmpty()
                    if (ids.containsMatchIn(id) && !id.contains("button") && !id.contains("navi") &&
                        listOf("name", "text", "input", "title").any(id::contains)) {
                        node.text?.let(::place)?.let { return it }
                    }
                }
                for (i in 0 until words.lastIndex) if (words[i] == label) place(words[i + 1])?.let { return it }
                return null
            }
            val destination = endpoint("终点", endId)
            if (words.any { it == "退出导航" || it == "结束导航" } && words.any { remaining.containsMatchIn(it) }) return Active(platform, destination)
            if (!words.any(::isStart) || !words.any { duration.containsMatchIn(it) }) return Other
            val origin = endpoint("起点", startId) ?: words.firstOrNull { it == "我的位置" || it == "当前位置" }
            return if (origin != null && destination != null) Overview(NavigationRoute(platform, origin, destination)) else Other
        }
    }
}
