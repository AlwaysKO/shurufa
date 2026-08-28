package com.yuyan.imemodule

import android.content.Context
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.ExpressionCommitDispatcher
import com.yuyan.imemodule.keyboard.SogouQwertyLayout
import com.yuyan.imemodule.keyboard.SogouT9Layout
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.expression.ui.ExpressionLayoutMetrics
import com.yuyan.imemodule.adapter.CandidatesMenuAdapter
import com.yuyan.imemodule.service.ImeService
import com.yuyan.inputmethod.RimeEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test

/** 本轮功能不能移除已发布 AAR 的既有 JVM 入口。 */
class PublicAbiCompatibilityTest {
    @Test
    fun `InputModeSwitcher保留原单参数和Pair签名`() {
        assertNotNull(InputModeSwitcher::class.java.getDeclaredMethod("switchModeForUserKey", Int::class.javaPrimitiveType))
        assertNotNull(InputModeSwitcher::class.java.getDeclaredMethod("switchModeForSetting", kotlin.Pair::class.java))
    }

    @Test
    fun `键盘与设置容器保留原构造签名`() {
        assertNotNull(SoftKeyboard::class.java.getDeclaredConstructor(List::class.java))
        assertNotNull(
            SoftKeyboard::class.java.getDeclaredConstructor(
                List::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ),
        )
        assertNotNull(SettingsContainer::class.java.getDeclaredConstructor(Context::class.java, InputView::class.java))
        assertNotNull(
            SoftKey::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
            ),
        )
    }

    @Test
    fun `核心原公共类型仍保留可构造或单例入口`() {
        assertNotNull(InputView::class.java.getDeclaredConstructor(Context::class.java, ImeService::class.java))
        assertNotNull(ImeService::class.java.getDeclaredConstructor())
        assertNotNull(RimeEngine::class.java.getDeclaredField("INSTANCE"))
    }

    @Test
    fun `既有几何数组模式保存符号和提交入口保持原描述符`() {
        assertNotNull(SogouQwertyLayout::class.java.getDeclaredMethod("getBottomRowWidths").takeIf { it.returnType == FloatArray::class.java })
        listOf("getColumnWidths", "getColumnLeftEdges", "getColumnRightEdges", "getBottomRowWidths").forEach { name ->
            assertNotNull(SogouT9Layout::class.java.getDeclaredMethod(name).takeIf { it.returnType == FloatArray::class.java })
        }
        assertNotNull(InputModeSwitcher::class.java.getDeclaredMethod("saveInputMode", Int::class.javaPrimitiveType))
        assertNotNull(SymbolContainer::class.java.getDeclaredMethod("setSymbolsView"))
        assertNotNull(
            ExpressionCommitDispatcher::class.java.getDeclaredMethod(
                "dispatch",
                String::class.java,
                kotlin.jvm.functions.Function1::class.java,
                kotlin.jvm.functions.Function1::class.java,
            ),
        )
    }

    @Test
    fun `面板尺寸保留原十参数构造copy和component10类型`() {
        val oldParameters = arrayOf(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        )
        assertNotNull(ExpressionLayoutMetrics::class.java.getDeclaredConstructor(*oldParameters))
        assertNotNull(ExpressionLayoutMetrics::class.java.getDeclaredMethod("copy", *oldParameters))
        assertEquals(Float::class.javaPrimitiveType, ExpressionLayoutMetrics::class.java.getDeclaredMethod("component10").returnType)
        assertNotNull(
            ExpressionLayoutMetrics.Companion::class.java.getDeclaredMethod(
                "calculate",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ),
        )
        assertNotNull(
            CandidatesMenuAdapter.SymbolHolder::class.java.declaredMethods.singleOrNull {
                it.name == "setEntranceIconImageView"
            },
        )
    }
}
