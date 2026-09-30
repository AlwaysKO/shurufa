package com.yuyan.imemodule.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接线回归，不替代 OEM 生命周期实测。 */
class ImeLifecycleGuardTest {
    private val source = File("src/main/java/com/yuyan/imemodule/service/ImeService.kt").readText()
    @Test fun `视图创建之前的光标回调不会访问lateinit`() {
        val method = source.substringAfter("override fun onUpdateSelection(").substringBefore("private val cursorAnchorPosition")
        assertTrue(method.contains("if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.onUpdateSelection"))
    }
    @Test fun `配置改变但没有编辑连接时不请求光标更新`() {
        val method = source.substringAfter("fun handleHardwareKeyboard(")
        assertTrue(method.contains("currentInputConnection?.requestCursorUpdates("))
    }
    @Test fun `候选更新回调在重置或联想前保留活跃组合`() {
        val method = File("src/main/java/com/yuyan/imemodule/keyboard/InputView.kt").readText()
            .substringAfter("fun onUpdateSelection(oldSelStart:")
        assertTrue(method.indexOf("if (Kernel.hasActiveComposition) return") in 0 until method.indexOf("when {"))
    }
}
