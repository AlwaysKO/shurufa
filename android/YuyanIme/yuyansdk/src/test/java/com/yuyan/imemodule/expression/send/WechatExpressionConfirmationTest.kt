package com.yuyan.imemodule.expression.send

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [WechatExpressionConfirmationTest.ServiceShadow::class, WechatExpressionConfirmationTest.NodeShadow::class])
class WechatExpressionConfirmationTest {
    class Observer : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent) {}
        override fun onInterrupt() {}
    }
    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation fun getRootInActiveWindow() = root?.let { source -> AccessibilityNodeInfo.obtain(source).also { (shadowOf(it) as NodeShadow).window = (shadowOf(source) as NodeShadow).window } }
        companion object { var root: AccessibilityNodeInfo? = null }
    }
    @Implements(AccessibilityNodeInfo::class)
    class NodeShadow : ShadowAccessibilityNodeInfo() {
        @RealObject private lateinit var real: AccessibilityNodeInfo
        var valid = true
        var window = 10
        @Implementation override fun refresh(): Boolean {
            val source = focused ?: return false
            val valid = (shadowOf(source) as NodeShadow).valid
            if (valid) { real.text = source.text; real.isPassword = source.isPassword }
            return valid
        }
        @Implementation override fun getWindowId() = window
        @Implementation fun findFocus(kind: Int) = focused?.let { AccessibilityNodeInfo.obtain(it) }
        companion object { var focused: AccessibilityNodeInfo? = null }
    }
    private lateinit var service: Observer
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var editor: AccessibilityNodeInfo
    private lateinit var connection: BaseInputConnection
    @Before fun setup() {
        service = Robolectric.buildService(Observer::class.java).create().get()
        root = AccessibilityNodeInfo.obtain().apply { packageName = "com.tencent.mm" }
        editor = AccessibilityNodeInfo.obtain().apply { packageName = "com.tencent.mm"; isEditable = true; text = "测试原文" }
        shadowOf(editor).setRefreshReturnValue(true)
        NodeShadow.focused = editor
        ServiceShadow.root = root
        connection = object : BaseInputConnection(View(ApplicationProvider.getApplicationContext()), true) {
            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) = ExtractedText().apply {
                text = "测试原文"; startOffset = 0; partialStartOffset = -1; partialEndOffset = -1
            }
        }
        WechatExpressionConfirmation.connect(service)
    }
    @After fun cleanup() { WechatExpressionConfirmation.disconnect(service); ServiceShadow.root = null; NodeShadow.focused = null }
    private fun event(type: Int, window: Int, vararg texts: String, pkg: String = "com.tencent.mm", cls: String = "android.widget.Button") {
        val event = AccessibilityEvent.obtain(type).apply {
            packageName = pkg; className = cls; eventTime = SystemClock.uptimeMillis()
            text.addAll(texts.toList())
        }
        org.robolectric.util.ReflectionHelpers.setField(event, "mSourceWindowId", window)
        WechatExpressionConfirmation.event(event)
    }
    private fun dialog() = event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 20, "发送到当前聊天", "取消", "发送", cls = "com.tencent.mm.ui.widget.dialog.a4")
    private fun sends() = shadowOf(editor).performedActions.count { it == AccessibilityNodeInfo.ACTION_SET_TEXT }
    @Test fun `交接不清空确认后只清原节点一次`() {
        assertNotNull(WechatExpressionConfirmation.arm(connection))
        dialog(); assertEquals(0, sends())
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(1, sends())
        assertEquals("", shadowOf(editor).performedActionsWithArgs.single().second.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
    }
    @Test fun `取消和无点击返回均保留原文`() {
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "取消")
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 10, cls = "com.tencent.mm.ui.LauncherUI")
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        assertEquals(0, sends())
    }
    @Test fun `文字变化其他窗口发送及切换应用不清空`() {
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 10, "发送")
        assertEquals(0, sends())
        editor.text = "新输入"
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
        editor.text = "测试原文"
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 30, pkg = "other.app")
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
    }
    @Test fun `改写后恢复原文也撤销旧确认`() {
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, 10)
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
    }
    @Test fun `返回后迟到发送不清空`() {
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 10, cls = "com.tencent.mm.ui.LauncherUI")
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
    }
    @Test fun `其他窗口同名按钮会撤销旧确认`() {
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 10, "发送")
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
    }
    @Test fun `确认框退出前不清空恢复后清一次`() {
        WechatExpressionConfirmation.arm(connection); dialog()
        (shadowOf(root) as NodeShadow).window = 20
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
        (shadowOf(root) as NodeShadow).window = 10
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 10, cls = "com.tencent.mm.ui.LauncherUI")
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(1, sends())
    }
    @Test fun `窗口先恢复但同次发送点击随后到达仍清一次`() {
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 10, cls = "com.tencent.mm.ui.LauncherUI")
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(1, sends())
    }
    @Test fun `清空被拒绝也不重复删除`() {
        shadowOf(editor).setOnPerformActionListener { _, _ -> false }
        WechatExpressionConfirmation.arm(connection); dialog()
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(1, sends())
    }
    @Test fun `不是图片确认弹框的发送按钮不能清空`() {
        WechatExpressionConfirmation.arm(connection)
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 20, "发送", "取消", cls = "com.tencent.mm.ui.widget.dialog.a4")
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
    }
    @Test fun `节点消失和密码框不清空`() {
        WechatExpressionConfirmation.arm(connection); dialog()
        (shadowOf(editor) as NodeShadow).valid = false
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, 20, "发送")
        assertEquals(0, sends())
        editor.isPassword = true
        assertNull(WechatExpressionConfirmation.arm(connection))
    }
}
