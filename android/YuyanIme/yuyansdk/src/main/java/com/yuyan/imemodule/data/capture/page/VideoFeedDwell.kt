package com.yuyan.imemodule.data.capture.page

/** 单个导航候选只等待一次；划走、撤权或协程取消均不能获得取帧许可。 */
internal suspend fun awaitVideoFeedDwell(
    enteredElapsed: Long,
    elapsed: () -> Long,
    current: () -> Boolean,
    wait: suspend (Long) -> Unit,
): Boolean {
    if (!current() || enteredElapsed < 0) return false
    val now = elapsed()
    if (now < enteredElapsed) return false
    val remaining = (3_000L - (now - enteredElapsed)).coerceAtLeast(0)
    if (remaining > 0) wait(remaining)
    return current() && elapsed() >= enteredElapsed && elapsed() - enteredElapsed >= 3_000L
}
