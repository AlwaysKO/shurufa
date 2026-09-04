package com.yuyan.imemodule.expression.send

import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionContentSenderTest {
    @Test
    fun `兼容读取 EditorInfo extras 并接受图片通配 MIME`() {
        val editorInfo = EditorInfo().also {
            EditorInfoCompat.setContentMimeTypes(it, arrayOf("image/*"))
        }

        assertTrue(supportsExpressionMimeType("image/webp", editorInfo) { emptyArray() })
        assertTrue(supportsExpressionMimeType("image/gif", editorInfo) { emptyArray() })
    }

    @Test
    fun `拒绝未声明图片能力的纯文本输入框`() {
        assertFalse(
            supportsExpressionMimeType(
                expressionMimeType = "image/png",
                editorInfo = EditorInfo(),
                fallbackMimeTypes = { arrayOf("text/plain") },
            ),
        )
    }

    @Test
    fun `Android 6 使用兼容 extras 且不读取 API 25 字段`() {
        val editorInfo = EditorInfo().also {
            EditorInfoCompat.setContentMimeTypes(it, arrayOf("image/*"))
        }
        var fallbackCalls = 0

        assertTrue(
            supportsExpressionMimeType("image/webp", editorInfo, sdkInt = 23) {
                fallbackCalls += 1
                error("不应读取 API 25 字段")
            },
        )
        assertTrue(fallbackCalls == 0)
    }
}
