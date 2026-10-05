package com.yuyan.imemodule.data.collect

/** Robolectric会回拨时钟但复用应用单例；测试结束清理冷却，不能污染后续测试。 */
internal fun resetGameWorkRuntimeForTest() {
    GameWorkRuntime.setGaming(false)
    val gate = GameWorkRuntime::class.java.getDeclaredField("gate").apply { isAccessible = true }.get(null)
    gate.javaClass.getDeclaredField("resumeAt").apply { isAccessible = true }.setLong(gate, 0L)
}

/** 测试间时钟回拨后，旧按键时间/未结束触摸不能伪装成本测试仍在输入。 */
internal fun resetImageInputForTest() {
    val schedule = ImageUploadRuntime::class.java.getDeclaredField("schedule").apply { isAccessible = true }.get(null)
    synchronized(schedule) {
        schedule.javaClass.getDeclaredField("lastActivity").apply { isAccessible = true }.set(schedule, null)
        val touches = schedule.javaClass.getDeclaredField("touches").apply { isAccessible = true }.get(schedule) as MutableSet<*>
        touches.clear()
    }
}
