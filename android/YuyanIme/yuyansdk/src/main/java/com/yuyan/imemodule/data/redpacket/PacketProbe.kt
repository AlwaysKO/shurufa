package com.yuyan.imemodule.data.redpacket

import android.content.Context
import android.os.SystemClock
import com.yuyan.imemodule.BuildConfig

/** Debug 真机诊断仅记录结构计数与布尔值，不记录会话名、正文、通知或图片。 */
internal object PacketProbe {
    fun state(context: Context, value: String) {
        if (BuildConfig.DEBUG) context.getSharedPreferences("redpacket-probe", Context.MODE_PRIVATE).edit()
            .putString("state", value).putLong("state_at", System.currentTimeMillis()).apply()
    }
    fun action(context: Context, value: String) {
        if (BuildConfig.DEBUG) context.getSharedPreferences("redpacket-probe", Context.MODE_PRIVATE).edit()
            .putString("action", value).putLong("action_at", System.currentTimeMillis()).apply()
    }
    fun event(context: Context, type: Int, window: Int, wechat: Boolean, sinceGesture: Long) {
        if (BuildConfig.DEBUG) context.getSharedPreferences("redpacket-probe", Context.MODE_PRIVATE).edit()
            .putInt("cancel_event",type).putInt("cancel_window",window).putBoolean("cancel_wechat",wechat)
            .putLong("cancel_after_gesture_ms",sinceGesture).apply()
    }
    fun scroll(context: Context, event: android.view.accessibility.AccessibilityEvent) {
        if (BuildConfig.DEBUG) context.getSharedPreferences("redpacket-probe", Context.MODE_PRIVATE).edit()
            .putInt("scroll_y",event.scrollY).putInt("scroll_x",event.scrollX)
            .putInt("scroll_dy",event.scrollDeltaY).putInt("scroll_dx",event.scrollDeltaX)
            .putInt("scroll_from",event.fromIndex).putInt("scroll_to",event.toIndex).apply()
    }
    fun notification(context: Context, candidate: Boolean) {
        if (BuildConfig.DEBUG) context.getSharedPreferences("redpacket-probe", Context.MODE_PRIVATE).edit()
            .putLong("notice_at",System.currentTimeMillis()).putBoolean("notice_candidate",candidate).apply()
    }
    fun visual(context: Context, frame: PacketVisualFrame) {
        if (BuildConfig.DEBUG) context.getSharedPreferences("redpacket-probe", Context.MODE_PRIVATE).edit()
            .putInt("visual_width",frame.width).putInt("visual_height",frame.height)
            .putInt("ocr_lines",frame.lines.size).putInt("orange_regions",frame.orangeRegions.size)
            .putInt("red_regions",frame.redRegions.size).putInt("menus",frame.menuDots.size)
            .putString("orange_bounds",frame.orangeRegions.joinToString())
            .putString("packet_label_shapes",frame.lines.filter { it.text.startsWith("微信") }.joinToString { line ->
                line.text.map { if (it in "微信红包") it else if (it.isLetterOrDigit()) '?' else '.' }.joinToString("")
            })
            .putInt("packet_labels",frame.lines.count { it.text.replace(Regex("\\s+"), "") == "微信红包" })
            .putString("packet_label_bounds",frame.lines.filter { it.text.replace(Regex("\\s+"), "") == "微信红包" }.joinToString { it.bounds.toString() })
            .apply()
    }
    private var lastWrite = -2_000L
    fun record(context: Context, nodes: Int, window: Int, width: Int, height: Int, page: PacketPage?, visual: Boolean = false) {
        if (!BuildConfig.DEBUG || SystemClock.uptimeMillis() - lastWrite < 2_000) return
        lastWrite = SystemClock.uptimeMillis()
        context.getSharedPreferences("redpacket-probe", Context.MODE_PRIVATE).edit()
            .putBoolean("visual", visual).putInt("nodes", nodes).putInt("window", window).putInt("width", width).putInt("height", height)
            .putBoolean("chat", page?.chatName != null).putBoolean("group_hint", page?.groupChat == true)
            .putInt("cards", page?.cards?.size ?: 0).putBoolean("info_button", page?.chatInfoButton != null)
            .putBoolean("group_details", page?.verifiedGroupDetails == true).putBoolean("panel", page?.packetPanel == true)
            .putBoolean("open_button", page?.openButton != null).putLong("observed_at", System.currentTimeMillis()).apply()
    }
}
