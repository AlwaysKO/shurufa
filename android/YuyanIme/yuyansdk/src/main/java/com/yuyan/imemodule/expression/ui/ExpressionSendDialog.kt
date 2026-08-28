package com.yuyan.imemodule.expression.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.yuyan.imemodule.R
import com.yuyan.imemodule.expression.send.ExpressionContentSender
import com.yuyan.imemodule.expression.send.ExpressionSendController
import com.yuyan.imemodule.expression.send.ExpressionSendResult
import com.yuyan.imemodule.expression.send.PreparedExpression
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun resetExpressionSendButtons(confirmButton: Button, cancelButton: Button) {
    confirmButton.isEnabled = true
    cancelButton.isEnabled = true
}

class ExpressionSendDialog(
    private val anchor: View,
    private val controller: ExpressionSendController,
    private val contentSender: ExpressionContentSender,
) : Dialog(anchor.context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preview: ImageView
    private val error: TextView
    private val fallbacks: LinearLayout
    private val cancelButton: Button
    private val confirmButton: Button
    private var expression: PreparedExpression? = null

    init {
        setContentView(R.layout.sdk_expression_send_sheet)
        preview = findViewById(R.id.expression_send_preview)
        error = findViewById(R.id.expression_send_error)
        fallbacks = findViewById(R.id.expression_send_fallbacks)
        cancelButton = findViewById(R.id.expression_send_cancel)
        confirmButton = findViewById(R.id.expression_send_confirm)
        cancelButton.setOnClickListener { cancelPrepared() }
        confirmButton.setOnClickListener { confirm() }
        findViewById<Button>(R.id.expression_send_save).setOnClickListener { save() }
        findViewById<Button>(R.id.expression_send_copy).setOnClickListener { copy() }
        setCanceledOnTouchOutside(true)
        setOnCancelListener { controller.cancel() }
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                gravity = Gravity.BOTTOM
                dimAmount = 0.55f
                width = WindowManager.LayoutParams.MATCH_PARENT
            }
        }
    }

    fun show(expression: PreparedExpression) {
        this.expression = expression
        error.visibility = View.GONE
        fallbacks.visibility = View.GONE
        resetExpressionSendButtons(confirmButton, cancelButton)
        confirmButton.setText(R.string.expression_send)
        Glide.with(preview).load(expression.file).fitCenter().into(preview)
        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
            attributes = attributes.apply { token = anchor.windowToken }
        }
        super.show()
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun confirm() {
        confirmButton.isEnabled = false
        cancelButton.isEnabled = false
        confirmButton.setText(R.string.expression_sending)
        scope.launch {
            when (val result = controller.confirm()) {
                ExpressionSendResult.Sent -> dismiss()
                ExpressionSendResult.UnsupportedTarget -> showFailure(context.getString(R.string.expression_chat_image_unsupported))
                is ExpressionSendResult.Failed -> showFailure(
                    result.reason.ifBlank { context.getString(R.string.expression_image_send_failed) },
                )
                ExpressionSendResult.AlreadySending -> Unit
                ExpressionSendResult.NotPrepared -> showFailure(context.getString(R.string.expression_image_expired))
            }
        }
    }

    private fun showFailure(reason: String) {
        confirmButton.isEnabled = true
        cancelButton.isEnabled = true
        confirmButton.setText(R.string.expression_retry_send)
        error.text = reason
        error.visibility = View.VISIBLE
        fallbacks.visibility = View.VISIBLE
    }

    private fun cancelPrepared() {
        controller.cancel()
        dismiss()
    }

    private fun save() {
        val current = expression ?: return
        scope.launch {
            error.text = context.getString(
                if (contentSender.saveToGallery(current)) R.string.expression_saved_gallery
                else R.string.expression_save_gallery_failed,
            )
            error.visibility = View.VISIBLE
        }
    }

    private fun copy() {
        val current = expression ?: return
        error.text = context.getString(
            if (contentSender.copyToClipboard(current)) R.string.expression_copied_image
            else R.string.expression_copy_image_failed,
        )
        error.visibility = View.VISIBLE
    }

    override fun dismiss() {
        Glide.with(preview).clear(preview)
        super.dismiss()
    }

    fun close() {
        controller.cancel()
        if (isShowing) dismiss()
    }

    fun destroy() {
        close()
        scope.cancel()
    }
}
