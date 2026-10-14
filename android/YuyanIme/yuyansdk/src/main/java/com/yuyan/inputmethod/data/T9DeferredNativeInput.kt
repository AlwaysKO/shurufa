package com.yuyan.inputmethod.data

/** 只记录原生仍持有的前缀长度；完整按键始终由 KeyRecordStack 持有。 */
internal class T9DeferredNativeInput {
    var nativeLength: Int = 0
        private set
    val isActive: Boolean get() = nativeLength > 0

    fun start(nativeLength: Int) {
        require(nativeLength > 0)
        check(!isActive)
        this.nativeLength = nativeLength
    }

    fun shouldHandleDelete(currentRawLen: Int): Boolean = isActive && currentRawLen > nativeLength

    fun afterDelete(newLen: Int) {
        if (isActive && newLen <= nativeLength) clear()
    }

    fun clear() {
        nativeLength = 0
    }
}
