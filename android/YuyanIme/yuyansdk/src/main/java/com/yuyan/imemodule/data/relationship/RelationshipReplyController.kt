package com.yuyan.imemodule.data.relationship

import com.yuyan.imemodule.data.capture.ActiveChatContext
import com.yuyan.imemodule.data.capture.ActiveChatContextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

class RelationshipReplyRefreshState {
    private var count = 0
    val aiEligible: Boolean get() = count >= 2
    fun start(): Int { count = 0; return count }
    fun next(): Int { count = (count + 1).coerceAtMost(20); return count }
    fun current(): Int = count
}

class RelationshipReplyController(
    private val contextStore: ActiveChatContextStore,
    private val client: RelationshipReplySource,
    private val scope: CoroutineScope,
) {
    private val refreshState = RelationshipReplyRefreshState()
    private var activeContext: ActiveChatContext? = null
    private var requestGeneration = 0L
    private var job: Job? = null
    private var activePackage: String? = null
    private var replySessionId: String? = null

    fun start(packageName: String?, publish: (RelationshipReplyResponse) -> Unit) {
        stop()
        if (packageName == null) return
        activePackage = packageName
        replySessionId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        refreshState.start()
        val generation = ++requestGeneration
        job = scope.launch {
            delay(CONTEXT_SETTLE_MILLIS)
            val context = contextStore.current(packageName) ?: return@launch
            if (context.observedAt < startedAt - MAX_PRESTART_AGE_MILLIS) return@launch
            activeContext = context
            fetchAndPublish(context, generation, refreshState.current(), publish)
        }
    }

    fun refresh(publish: (RelationshipReplyResponse) -> Unit) {
        if (activeContext == null) return
        refreshState.next()
        request(publish)
    }

    fun stop() {
        requestGeneration += 1
        job?.cancel()
        job = null
        activeContext = null
        activePackage = null
        replySessionId = null
    }

    private fun request(publish: (RelationshipReplyResponse) -> Unit) {
        val context = activeContext ?: return
        val generation = ++requestGeneration
        val refreshCount = refreshState.current()
        job?.cancel()
        job = scope.launch {
            fetchAndPublish(context, generation, refreshCount, publish)
        }
    }

    private suspend fun fetchAndPublish(
        context: ActiveChatContext,
        generation: Long,
        refreshCount: Int,
        publish: (RelationshipReplyResponse) -> Unit,
    ) {
        val sessionId = replySessionId ?: return
        val result = runCatching { client.fetch(context, refreshCount, sessionId) }.getOrNull() ?: return
        val current = activePackage?.let(contextStore::current) ?: return
        if (generation == requestGeneration && activeContext == context &&
            sameActiveContext(context, current)
        ) publish(result)
    }

    private companion object {
        const val CONTEXT_SETTLE_MILLIS = 350L
        const val MAX_PRESTART_AGE_MILLIS = 250L
    }
}

internal fun sameActiveContext(requested: ActiveChatContext, current: ActiveChatContext): Boolean =
    current.accountKey == requested.accountKey && current.externalKey == requested.externalKey &&
        current.observedAt >= requested.observedAt
