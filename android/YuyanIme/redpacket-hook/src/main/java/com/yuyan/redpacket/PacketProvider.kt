package com.yuyan.redpacket

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

object CallerPolicy {
    fun allowed(caller: Int, own: Int, packages: Set<String>, module: String): Boolean =
        caller == own || (caller / 100000 == own / 100000 && "com.tencent.mm" in packages && module !in packages)
}

class PacketProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (!CallerPolicy.allowed(uid, Process.myUid(), ctx.packageManager.getPackagesForUid(uid)?.toSet().orEmpty(), ctx.packageName))
            throw SecurityException("Caller not permitted")
        val prefs = ctx.getSharedPreferences("packet", 0)
        val config = ConfigStore(prefs).load()
        return when (method) {
            "config" -> Bundle().apply {
                putString("mode", config.mode.name)
                putInt("user", config.user)
                putStringArrayList("groups", ArrayList(config.groups))
                putBoolean("allowed", config.mode == Mode.AUTO && config.user == Process.myUid() / 100000 &&
                    prefs.getBoolean("validated", false) && ProtectionService.allowed())
            }
            "reserve" -> Bundle().apply {
                val key = extras?.getString("key").orEmpty()
                putBoolean("reserved", config.mode == Mode.AUTO && config.user == Process.myUid() / 100000 &&
                    prefs.getBoolean("validated", false) && ProtectionService.allowed() && PacketJournal(prefs).reserve(key))
            }
            "report" -> Bundle().apply {
                val status = extras?.getString("status")
                if (status in REPORT_STATUSES) synchronized(REPORT_LOCK) {
                    val count = prefs.getLong("count_$status", 0)
                    prefs.edit().putString("last_status", status).putLong("last_signal", System.currentTimeMillis())
                        .putLong("count_$status", if (count == Long.MAX_VALUE) count else count + 1).apply()
                }
            }
            else -> throw IllegalArgumentException("Unknown operation")
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
    companion object {
        val URI: Uri = Uri.parse("content://com.yuyan.redpacket.config")
        val PROTECTION_URI: Uri = URI.buildUpon().appendPath("protection").build()
        val REPORT_STATUSES = setOf("ready", "incompatible", "candidate", "probe", "protected", "duplicate", "busy", "journal_full", "receiving", "opening", "claimed", "rejected", "failed", "unknown", "stopped", "queue_full")
        private val REPORT_LOCK = Any()
    }
}
