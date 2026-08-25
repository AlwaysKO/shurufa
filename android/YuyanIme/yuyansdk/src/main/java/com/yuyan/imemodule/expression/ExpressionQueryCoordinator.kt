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
    private var preserveCommittedQuery = false
    private var closed = false

    init {
        require(debounceMillis >= 0) { "debounceMillis must not be negative" }
    }

    fun onFirstCandidate(text: String?) {
        if (closed) return
        val query = text?.trim()?.takeIf(String::isNotEmpty)
        if (query == null && preserveCommittedQuery) {
            preserveCommittedQuery = false
            return
        }
        preserveCommittedQuery = false
        updateQuery(query)
    }

    fun onCommitted(text: String) {
        if (closed) return
        preserveCommittedQuery = true
        text.trim().takeIf(String::isNotEmpty)?.let(::updateQuery)
    }

    fun acceptResponse(requestId: Long): Boolean =
        !closed && currentQuery != null && requestId == this.requestId

    fun close() {
        if (closed) return
        closed = true
        pendingQuery?.cancel()
        pendingQuery = null
    }

    private fun updateQuery(query: String?) {
        if (query == currentQuery) return
        currentQuery = query
        requestId += 1
        pendingQuery?.cancel()
        pendingQuery = null
        if (query == null) return

        val scheduledRequestId = requestId
        pendingQuery = scope.launch {
            delay(debounceMillis)
            if (!closed && scheduledRequestId == requestId && currentQuery == query) {
                publishQuery(query)
            }
        }
    }
}
