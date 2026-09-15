package com.yuyan.imemodule.expression

import android.content.Context
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.expression.ui.resetExpressionSendButtons
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionSendDialogStateTest {
    @Test
    fun reopeningAfterSendReenablesBothActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val confirm = Button(context).apply { isEnabled = false }
        val cancel = Button(context).apply { isEnabled = false }

        resetExpressionSendButtons(confirm, cancel)

        assertTrue(confirm.isEnabled)
        assertTrue(cancel.isEnabled)
    }
}
