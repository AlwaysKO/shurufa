package com.yuyan.imemodule.data.capture.ui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccessibilitySemanticSnapshotTest {
    @Suppress("DEPRECATION")
    @Test fun realNodeReaderKeepsSemanticsWhenResourceIdAndClassAreUnavailable() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.isEditable = true; node.isScrollable = true; node.isVisibleToUser = true
            node.setBoundsInScreen(Rect(0, 0, 100, 100))
            val result = AccessibilityTreeReader().read(node)!!
            val encoded = Json.encodeToJsonElement(result).jsonObject
            assertEquals(JsonPrimitive(true), encoded["editable"])
            assertEquals(JsonPrimitive(true), encoded["scrollable"])
        } finally { node.recycle() }
    }
    @Suppress("DEPRECATION")
    @Test fun realNodeReaderDoesNotCopyPasswordTextOrDescription() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.isEditable = true; node.isPassword = true; node.isVisibleToUser = true
            node.text = "synthetic-secret"; node.contentDescription = "synthetic-secret"
            node.setBoundsInScreen(Rect(0, 0, 100, 100))
            val result = AccessibilityTreeReader().read(node)!!
            assertNull(result.text); assertNull(result.contentDescription)
            assertEquals(JsonPrimitive(true), Json.encodeToJsonElement(result).jsonObject["password"])
        } finally { node.recycle() }
    }
}
