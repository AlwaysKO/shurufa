package com.yuyan.imemodule.data.redpacket

import android.content.Context
import androidx.preference.PreferenceManager

internal object SilentPacketSettings {
    private const val MODE = "wechat_silent_packet_mode"
    private const val USER = "wechat_silent_packet_user"
    private const val GROUPS = "wechat_silent_packet_groups"
    private const val AUTO_USER = "wechat_silent_packet_auto_user"
    private const val AUTO_GROUPS = "wechat_silent_packet_auto_groups"
    private val modes = setOf("OFF", "PROBE", "AUTO")
    private fun preferences(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun autoUser(context: Context): Boolean = preferences(context).let {
        it.getBoolean(AUTO_USER, !it.contains(USER))
    }
    fun setAutoUser(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(AUTO_USER, enabled).apply()
    }
    fun autoGroups(context: Context): Boolean = preferences(context).let {
        it.getBoolean(AUTO_GROUPS, !it.contains(GROUPS))
    }
    fun setAutoGroups(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(AUTO_GROUPS, enabled).apply()
    }
    fun allowsGroup(context: Context, name: String): Boolean =
        name.isNotBlank() && (autoGroups(context) || name in groups(context))
    fun targetUser(context: Context, noticeUser: Int): Int? = when {
        noticeUser < 0 -> null
        autoUser(context) -> noticeUser
        else -> userId(context).takeIf { it >= 0 && it == noticeUser }
    }

    fun mode(context: Context): String = preferences(context).getString(MODE, "OFF")
        ?.takeIf { it in modes } ?: "OFF"

    fun setMode(context: Context, mode: String) {
        require(mode in modes) { "Unknown silent packet mode" }
        if (mode != "OFF") PacketSettings.setEnabled(context, false)
        preferences(context).edit().putString(MODE, mode).apply()
    }

    fun userId(context: Context): Int = preferences(context).getInt(USER, -1).takeIf { it >= 0 } ?: -1

    fun setUserId(context: Context, userId: Int) {
        require(userId >= -1) { "Invalid Android user ID" }
        preferences(context).edit().putInt(USER, userId).apply()
    }

    fun groups(context: Context): Set<String> = preferences(context).getString(GROUPS, "").orEmpty()
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(linkedSetOf())

    fun setGroups(context: Context, text: String) {
        preferences(context).edit().putString(GROUPS,
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).apply()
    }
}
