package com.yuyan.redpacket

import android.app.Application
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.tencent.mm" || param.processName != "com.tencent.mm") return
        val once = AtomicBoolean()
        XposedBridge.hookMethod(Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java), object : XC_MethodHook() {
            override fun afterHookedMethod(call: MethodHookParam) {
                if (call.hasThrowable() || !once.compareAndSet(false, true)) return
                val app = call.args.firstOrNull() as? Application ?: return
                HookRuntime(app).start()
            }
        })
    }
}

internal class HookRuntime(private val context: Context) {
    private class Identity(val value: Any) {
        override fun hashCode() = System.identityHashCode(value)
        override fun equals(other: Any?) = other is Identity && other.value === value
    }
    private val owned = ConcurrentHashMap.newKeySet<Identity>()
    private val dropped = AtomicInteger()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { action -> Thread(action, "redpacket-hook").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } })
    private val timer = ScheduledThreadPoolExecutor(1) { action -> Thread(action, "redpacket-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private var deadlineGeneration = 0L
    private var deadline: java.util.concurrent.ScheduledFuture<*>? = null
    private var engine: PacketEngine? = null
    @Volatile private var config = PacketConfig()
    private val hooks = mutableListOf<XC_MethodHook.Unhook>()
    private var observer: ContentObserver? = null
    private fun call(method: String, extras: Bundle = Bundle()): Bundle? = runCatching {
        context.contentResolver.call(PacketProvider.URI, method, null, extras)
    }.getOrNull()
    private fun report(status: String) { call("report", Bundle().apply { putString("status", status) }) }
    private fun readConfig(): PacketConfig {
        val result = call("config") ?: return PacketConfig()
        return PacketConfig(runCatching { Mode.valueOf(result.getString("mode").orEmpty()) }.getOrDefault(Mode.OFF),
            result.getInt("user", -1), result.getStringArrayList("groups")?.toSet().orEmpty())
    }
    private fun submit(action: () -> Unit) {
        try {
            worker.execute {
                if (dropped.getAndSet(0) > 0) report("queue_full")
                try { action() } catch (_: Throwable) { engine?.stop(); report("unknown") }
            }
        } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }
    fun start() = submit {
        val pkg = context.packageManager.getPackageInfo("com.tencent.mm", 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
        val user = Process.myUid() / 100000
        val selected = readConfig()
        if (selected.mode == Mode.OFF || selected.user != user) {
            report("stopped"); worker.shutdown(); timer.shutdown(); return@submit
        }
        if (!selected.acceptHost(context.packageName, context.packageName, user, pkg.versionName.orEmpty(), code) ||
            apkDigest(File(context.applicationInfo.sourceDir)) != Wechat8078.APK_SHA256) {
            report("incompatible"); worker.shutdown(); timer.shutdown(); return@submit
        }
        val binding = Wechat8078.resolve(context.classLoader)
        if (binding == null) { report("incompatible"); worker.shutdown(); timer.shutdown(); return@submit }
        val port = object : PacketPort {
            override fun config() = readConfig().also { config = it }
            override fun allowed() = call("config")?.getBoolean("allowed", false) == true
            override fun reserve(key: String): Boolean = call("reserve", Bundle().apply { putString("key", key) })?.getBoolean("reserved", false) == true
            override fun receive(packet: Packet) = binding.receive(packet)
            override fun open(packet: Packet, token: String) = binding.open(packet, token)
            override fun send(request: Any, callback: (Any, Boolean) -> Unit): Boolean {
                owned.add(Identity(request))
                return binding.send(request) { scene, success -> submit { callback(scene, success); scheduleDeadline() } }
            }
            override fun report(status: String) = this@HookRuntime.report(status)
            override fun release(request: Any) { owned.remove(Identity(request)) }
        }
        engine = PacketEngine(port, user, SystemClock::elapsedRealtime)
        try {
            hooks += XposedBridge.hookMethod(binding.dispatch, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { binding.capture(param.args.firstOrNull()) }
            })
            hooks += XposedBridge.hookMethod(binding.receiveEnd, businessHook(true))
            hooks += XposedBridge.hookMethod(binding.openEnd, businessHook(false))
            var methods = 0
            for (name in listOf("com.tencent.wcdb.database.SQLiteDatabase", "com.tencent.wcdb.compat.SQLiteDatabase")) {
                val db = context.classLoader.loadClass(name)
                for (method in listOf("insert", "insertOrThrow", "replace", "insertWithOnConflict")) {
                    val types = if (method == "insertWithOnConflict") arrayOf(String::class.java, String::class.java, ContentValues::class.java, Int::class.javaPrimitiveType!!)
                        else arrayOf(String::class.java, String::class.java, ContentValues::class.java)
                    val member = db.getDeclaredMethod(method, *types)
                    require(member.returnType == Long::class.javaPrimitiveType)
                    hooks += XposedBridge.hookMethod(member, databaseHook())
                    methods++
                }
            }
            require(methods == 8)
            config = readConfig()
            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                    submit {
                        config = readConfig()
                        if (uri != PacketProvider.PROTECTION_URI) engine?.stop()
                        else engine?.resumeDeferred()
                        scheduleDeadline()
                    }
                }
            }.also { context.contentResolver.registerContentObserver(PacketProvider.URI, true, it) }
            report("ready")
        } catch (_: Throwable) {
            config = PacketConfig()
            hooks.forEach { runCatching { it.unhook() } }; hooks.clear()
            report("incompatible"); worker.shutdown(); timer.shutdown()
        }
    }
    private fun databaseHook() = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                if (config.mode == Mode.OFF || param.hasThrowable() || param.args.firstOrNull() != "message") return
                val row = param.result as? Long ?: return
                if (row < 0) return
                val values = param.args.getOrNull(2) as? ContentValues ?: return
                if (values.getAsInteger("type") != 436207665 || values.getAsInteger("isSend") != 0) return
                val talker = values.getAsString("talker") ?: return
                if (!talker.endsWith("@chatroom")) return
                val text = values.getAsString("content") ?: return
                if (text.length > 32768) return
                val snapshot = StoredMessage(436207665, 0, talker, text, values.getAsLong("createTime"))
                submit {
                    PacketParser.parse(snapshot, row, System.currentTimeMillis())?.let { engine?.accept(it) }
                    scheduleDeadline()
                }
            } catch (_: Throwable) { /* 不影响微信写入、返回值或异常。 */ }
        }
    }
    private fun businessHook(receive: Boolean) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val request = param.thisObject ?: return
            if (!owned.contains(Identity(request))) return
            try {
                val error = param.args.getOrNull(0) as? Int ?: return
                val json = param.args.getOrNull(2) as? JSONObject ?: return
                val ok = !param.hasThrowable() && if (receive) ResponsePolicy.receiveAllowed(error, json) else ResponsePolicy.claimed(error, json)
                val token = if (receive && ok) json.optString("timingIdentifier") else ""
                submit {
                    if (receive) engine?.receiveBusiness(request, ok, token) else engine?.openBusiness(request, ok)
                    scheduleDeadline()
                }
            } catch (_: Throwable) { submit { engine?.stop(); scheduleDeadline() } }
        }
    }
    private fun scheduleDeadline() {
        deadlineGeneration++
        deadline?.cancel(false)
        deadline = null
        val delay = engine?.nextDeadlineDelay() ?: return
        val generation = deadlineGeneration
        deadline = timer.schedule({
            submit {
                if (generation == deadlineGeneration) {
                    deadline = null
                    engine?.expire()
                    scheduleDeadline()
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }
    private fun apkDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
