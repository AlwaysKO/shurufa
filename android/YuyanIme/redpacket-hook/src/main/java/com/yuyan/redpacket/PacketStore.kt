package com.yuyan.redpacket

import android.content.SharedPreferences
import org.json.JSONObject

/** 不淘汰未知结果；容量耗尽时拒绝新任务，避免重启/重复入库导致再次拆开。 */
class PacketJournal(private val prefs: SharedPreferences, private val capacity: Int = 2048) {
    fun reserve(key: String): Boolean = synchronized(LOCK) {
        if (!key.matches(Regex("[a-f0-9]{64}"))) return false
        val seen = prefs.getStringSet("attempts", emptySet())!!.toMutableSet()
        if (key in seen || seen.size >= capacity) return false
        seen.add(key)
        return prefs.edit().putStringSet("attempts", seen).commit()
    }
    companion object { private val LOCK = Any() }
}

class ProtectionGate {
    private var blocked = false
    private var resumeAt = 0L
    @Synchronized fun allow(now: Long, service: Boolean, foregroundKnown: Boolean, typing: Boolean, gaming: Boolean): Boolean {
        if (!service || !foregroundKnown || typing || gaming) { blocked = true; return false }
        if (blocked) { resumeAt = now + 3000; blocked = false }
        return now >= resumeAt
    }
}

object ResponsePolicy {
    private val challenges = setOf("real_name_info", "intercept_win", "intercept_win_after", "showmess", "agree_duty", "jumpRemind")
    private fun challenge(json: JSONObject) = challenges.any { json.has(it) && !json.isNull(it) }
    fun receiveAllowed(error: Int, json: JSONObject): Boolean = error == 0 && !challenge(json) &&
        json.has("hbStatus") && json.optInt("hbStatus", -1) == 2 &&
        json.has("receiveStatus") && json.optInt("receiveStatus", -1) == 0 &&
        json.has("hbType") && json.optInt("hbType", -1) in 0..1 &&
        json.optString("timingIdentifier").isNotBlank()
    fun claimed(error: Int, json: JSONObject): Boolean = error == 0 && !challenge(json) &&
        json.has("amount") && json.optLong("amount", 0) > 0 &&
        json.has("receiveStatus") && json.optInt("receiveStatus", -1) == 2 &&
        json.has("hbStatus") && json.optInt("hbStatus", -1) in 2..4 &&
        json.optInt("retcode", 0) == 0
}

class ConfigStore(private val prefs: SharedPreferences) {
    fun load(): PacketConfig {
        val mode = runCatching { Mode.valueOf(prefs.getString("mode", "OFF")!!) }.getOrDefault(Mode.OFF)
        return PacketConfig(mode, prefs.getInt("user", -1), prefs.getStringSet("groups", emptySet())!!.toSet())
    }
    fun save(config: PacketConfig, validated: Boolean) {
        val mode = if (config.mode == Mode.AUTO && !validated) Mode.PROBE else config.mode
        check(prefs.edit().putString("mode", mode.name).putInt("user", config.user)
            .putStringSet("groups", config.groups.toSet()).putBoolean("validated", validated).commit())
    }
}

enum class Foreground { UNKNOWN, OVERLAY, APP, GAME }
class ForegroundProtection {
    private val gate = ProtectionGate()
    private var remembered = Foreground.UNKNOWN
    private var reliable = false
    var canResume = false; private set
    @Synchronized fun allow(now: Long, interactive: Boolean, foreground: Foreground, typing: Boolean): Boolean {
        if (interactive) {
            if (foreground == Foreground.APP || foreground == Foreground.GAME) {
                remembered = foreground
                reliable = true
            } else if (foreground == Foreground.UNKNOWN) reliable = false
        }
        val known = when {
            !interactive || foreground == Foreground.OVERLAY -> reliable && remembered != Foreground.UNKNOWN
            else -> foreground == Foreground.APP || foreground == Foreground.GAME
        }
        canResume = known && !typing && remembered != Foreground.GAME
        return gate.allow(now, true, known, typing, remembered == Foreground.GAME)
    }
}
