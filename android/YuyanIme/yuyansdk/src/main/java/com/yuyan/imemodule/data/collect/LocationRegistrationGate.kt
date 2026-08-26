package com.yuyan.imemodule.data.collect

import java.util.concurrent.atomic.AtomicBoolean

/** 防止输入法生命周期并发触发时重复注册同一种定位监听器。 */
internal class LocationRegistrationGate {
    private val started = AtomicBoolean(false)

    fun tryStart(): Boolean = started.compareAndSet(false, true)

    fun reset() {
        started.set(false)
    }
}
