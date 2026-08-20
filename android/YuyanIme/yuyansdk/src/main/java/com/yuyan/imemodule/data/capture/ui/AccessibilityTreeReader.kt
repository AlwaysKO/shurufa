package com.yuyan.imemodule.data.capture.ui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal interface SnapshotNodeSource {
    val viewId: String?
    val className: String?
    val text: String?
    val contentDescription: String?
    val bounds: IntRect
    val childCount: Int
    fun childAt(index: Int): SnapshotNodeSource?
    fun close() = Unit
}

internal fun snapshotTree(
    root: SnapshotNodeSource?,
    maxDepth: Int,
    maxNodes: Int,
): UiNodeSnapshot? {
    if (root == null || maxDepth < 0 || maxNodes <= 0) return null
    var copiedNodes = 0

    fun copy(source: SnapshotNodeSource, depth: Int): UiNodeSnapshot? {
        if (copiedNodes >= maxNodes) return null
        copiedNodes += 1
        val children = mutableListOf<UiNodeSnapshot>()
        if (depth < maxDepth) {
            for (index in 0 until source.childCount) {
                if (copiedNodes >= maxNodes) break
                val child = source.childAt(index) ?: continue
                try {
                    copy(child, depth + 1)?.let(children::add)
                } finally {
                    child.close()
                }
            }
        }
        return UiNodeSnapshot(
            viewId = source.viewId,
            className = source.className,
            text = source.text,
            contentDescription = source.contentDescription,
            bounds = source.bounds,
            children = children,
        )
    }

    return copy(root, depth = 0)
}

class AccessibilityTreeReader(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {
    fun read(root: AccessibilityNodeInfo?): UiNodeSnapshot? = snapshotTree(
        root = root?.let(::AccessibilityNodeSource),
        maxDepth = maxDepth,
        maxNodes = maxNodes,
    )

    private class AccessibilityNodeSource(
        private val node: AccessibilityNodeInfo,
        private val ownsNode: Boolean = false,
    ) : SnapshotNodeSource {
        override val viewId: String? get() = node.viewIdResourceName
        override val className: String? get() = node.className?.toString()
        override val text: String? get() = node.text?.toString()
        override val contentDescription: String? get() = node.contentDescription?.toString()
        override val bounds: IntRect
            get() {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                return IntRect(rect.left, rect.top, rect.right, rect.bottom)
            }
        override val childCount: Int get() = node.childCount
        override fun childAt(index: Int): SnapshotNodeSource? =
            node.getChild(index)?.let { AccessibilityNodeSource(it, ownsNode = true) }

        @Suppress("DEPRECATION")
        override fun close() {
            if (ownsNode) node.recycle()
        }
    }

    private companion object {
        const val DEFAULT_MAX_DEPTH = 40
        const val DEFAULT_MAX_NODES = 2_000
    }
}
