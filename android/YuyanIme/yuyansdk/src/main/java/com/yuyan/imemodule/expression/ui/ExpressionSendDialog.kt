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
                token = anchor.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
        }
    }

    fun show(expression: PreparedExpression) {
        this.expression = expression
        controller.prepare(expression)
        error.visibility = View.GONE
        fallbacks.visibility = View.GONE
        confirmButton.isEnabled = true
        confirmButton.text = "发送"
        Glide.with(preview).load(expression.file).fitCenter().into(preview)
        super.show()
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun confirm() {
        confirmButton.isEnabled = false
        cancelButton.isEnabled = false
        confirmButton.text = "发送中…"
        scope.launch {
            when (val result = controller.confirm()) {
                ExpressionSendResult.Sent -> dismiss()
                ExpressionSendResult.UnsupportedTarget -> showFailure("当前聊天不支持图片输入")
                is ExpressionSendResult.Failed -> showFailure(result.reason)
                ExpressionSendResult.AlreadySending -> Unit
                ExpressionSendResult.NotPrepared -> showFailure("待发送图片已失效")
            }
        }
    }

    private fun showFailure(reason: String) {
        confirmButton.isEnabled = true
        cancelButton.isEnabled = true
        confirmButton.text = "重试发送"
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
            error.text = if (contentSender.saveToGallery(current)) "已保存到相册" else "保存到相册失败"
            error.visibility = View.VISIBLE
        }
    }

    private fun copy() {
        val current = expression ?: return
        error.text = if (contentSender.copyToClipboard(current)) "已复制图片" else "复制图片失败"
        error.visibility = View.VISIBLE
    }

    override fun dismiss() {
        Glide.with(preview).clear(preview)
        super.dismiss()
    }

    fun close() {
        controller.cancel()
        scope.cancel()
        if (isShowing) dismiss()
    }
}
