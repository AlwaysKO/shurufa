package com.yuyan.imemodule.data.collect

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Connection contract only; timing/gestures are exercised by ImageUploadScheduleTest. */
class ImageUploadInputHookTest {
    @Test fun `soft keyboard and candidates observe all dispatched touches and detach cancellation`() {
        for (path in listOf("keyboard/InputView.kt", "view/CandidatesBar.kt")) {
            val source = File("src/main/java/com/yuyan/imemodule/$path").readText()
            assertTrue(path, source.contains("override fun dispatchTouchEvent(event: MotionEvent): Boolean"))
            assertTrue(path, source.contains("ImageUploadRuntime.noteTouch(event.actionMasked, this)"))
            assertTrue(path, source.contains("ImageUploadRuntime.noteTouch(MotionEvent.ACTION_CANCEL, this)"))
        }
    }

    @Test fun `hardware activity is marked before key dispatch without content`() {
        val source = File("src/main/java/com/yuyan/imemodule/service/ImeService.kt").readText()
        val method = source.substringAfter("override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {")
        assertTrue(method.trimStart().startsWith("ImageUploadRuntime.noteKeyActivity()"))
    }
}
