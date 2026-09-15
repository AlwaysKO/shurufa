package com.yuyan.imemodule.expression

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ExpressionQueryCoordinator(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val publishQuery: (String) -> Unit,
) {
    private var pendingQuery: Job? = null
    private var currentQuery: String? = null
    private var requestId = 0L
    private var closed = false

    init {
        require(debounceMillis >= 0) { "debounceMillis must not be negative" }
    }

    fun onComposingChanged(text: String?): Boolean {
        if (closed) return false
        if (text.isNullOrBlank()) return false
        if (currentQuery == null && pendingQuery == null) return false
        updateQuery(null)
        return true
    }

    fun onCandidatesChanged(text: String?, isAssociate: Boolean): Boolean =
        onComposingChanged(text.takeUnless { isAssociate })

    fun onCommitted(text: String) {
        if (closed) return
        text.trim().takeIf(String::isNotEmpty)?.let(::updateQuery)
    }

    /** 工具栏手动搜索：取消自动查询的防抖任务并同步发布新查询。 */
    fun searchImmediately(text: String): Boolean {
        if (closed) return false
        val query = text.trim().takeIf(String::isNotEmpty) ?: return false
        currentQuery = query
        requestId += 1
        pendingQuery?.cancel()
        pendingQuery = null
        publishQuery(query)
        return true
    }

    fun acceptResponse(requestId: Long): Boolean =
        !closed && currentQuery != null && requestId == this.requestId

    fun reset() {
        if (closed) return
        updateQuery(null)
    }

    fun close() {
        if (closed) return
        closed = true
        pendingQuery?.cancel()
        pendingQuery = null
    }

    private fun updateQuery(query: String?): Boolean {
        if (query == currentQuery) return false
        currentQuery = query
        requestId += 1
        pendingQuery?.cancel()
        pendingQuery = null
        if (query == null) return true

        val scheduledRequestId = requestId
        pendingQuery = scope.launch {
            delay(debounceMillis)
            if (!closed && scheduledRequestId == requestId && currentQuery == query) {
                publishQuery(query)
            }
        }
        return false
    }
}
