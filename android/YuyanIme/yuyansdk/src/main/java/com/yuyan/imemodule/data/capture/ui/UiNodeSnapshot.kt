package com.yuyan.imemodule.data.capture.ui

import com.yuyan.imemodule.data.capture.sha256
import kotlinx.serialization.Serializable

@Serializable
data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class UiNodeSnapshot(
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: IntRect,
    val children: List<UiNodeSnapshot>,
)

internal fun preferredAccessibilitySnapshot(
    activeRoot: UiNodeSnapshot?,
    eventSource: UiNodeSnapshot?,
): UiNodeSnapshot? = when {
    activeRoot.isUsableAccessibilitySnapshot() -> activeRoot
    eventSource.isUsableAccessibilitySnapshot() -> eventSource
    else -> activeRoot ?: eventSource
}

internal fun UiNodeSnapshot?.isUsableAccessibilitySnapshot(): Boolean =
    this != null && bounds.right > bounds.left && bounds.bottom > bounds.top &&
        (className != null || viewId != null || text != null || contentDescription != null || children.isNotEmpty())

fun UiNodeSnapshot.stableTreeSignature(): String {
    val canonical = buildString { appendCanonicalNode(this@stableTreeSignature) }
    return sha256(canonical.toByteArray(Charsets.UTF_8))
}

private fun StringBuilder.appendCanonicalNode(node: UiNodeSnapshot) {
    appendValue(node.viewId)
    appendValue(node.className)
    appendValue(node.text)
    appendValue(node.contentDescription)
    append(node.bounds.left).append(',')
        .append(node.bounds.top).append(',')
        .append(node.bounds.right).append(',')
        .append(node.bounds.bottom).append(';')
    append(node.children.size).append('[')
    node.children.forEach(::appendCanonicalNode)
    append(']')
}

private fun StringBuilder.appendValue(value: String?) {
    if (value == null) append("-1:") else append(value.length).append(':').append(value)
    append('|')
}
