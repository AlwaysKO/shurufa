package com.yuyan.imemodule.data.capture.ui

import org.junit.Assert.assertSame
import org.junit.Test

class PreferredAccessibilitySnapshotTest {
    private val emptyRoot = UiNodeSnapshot(null, null, null, null, IntRect(0, 0, 0, 0), emptyList())
    private val eventTree = UiNodeSnapshot(
        null,
        "android.view.ViewGroup",
        null,
        null,
        IntRect(0, 0, 1200, 2664),
        listOf(UiNodeSnapshot("chatting_content_et", "android.widget.EditText", null, null, IntRect(100, 2400, 900, 2550), emptyList())),
    )

    @Test
    fun `event source replaces honor empty active root`() {
        assertSame(eventTree, preferredAccessibilitySnapshot(emptyRoot, eventTree))
    }

    @Test
    fun `valid active root remains authoritative`() {
        assertSame(eventTree, preferredAccessibilitySnapshot(eventTree, emptyRoot))
    }
}
