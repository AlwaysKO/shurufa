package com.yuyan.imemodule.expression

import kotlinx.coroutines.Job

/**
 * 以请求代次持有唯一预览任务。
 *
 * 网络回调即使已经在旧请求的 accept 阶段通过，只要新请求先开始，旧任务也只能被取消，
 * 不能再取消或覆盖新请求的预览任务。
 */
internal class ExpressionPreviewJobSlot {
    private var requestId = Long.MIN_VALUE
    private var previewJob: Job? = null

    @Synchronized
    fun beginRequest(requestId: Long) {
        this.requestId = requestId
        previewJob?.cancel()
        previewJob = null
    }

    fun installIfCurrent(requestId: Long, candidate: Job): Boolean {
        val accepted = synchronized(this) {
            if (requestId != this.requestId) {
                false
            } else {
                previewJob?.cancel()
                previewJob = candidate
                true
            }
        }
        if (accepted) candidate.start() else candidate.cancel()
        return accepted
    }

    @Synchronized
    fun cancel() {
        requestId += 1
        previewJob?.cancel()
        previewJob = null
    }
}
