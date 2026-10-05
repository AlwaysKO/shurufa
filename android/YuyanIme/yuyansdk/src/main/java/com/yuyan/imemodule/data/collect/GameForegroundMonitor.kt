package com.yuyan.imemodule.data.collect

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.BuildConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal enum class GameForegroundWindow { Application, Overlay, Unknown }
internal data class GameForegroundResult(val window: GameForegroundWindow, val gaming: Boolean? = null)

internal class GameForegroundState(
    private val query: (String, Int, Boolean?, (GameForegroundResult) -> Unit) -> Unit,
    private val publish: (Boolean) -> Unit,
    private val cacheLimit: Int = 64,
    private val schedule: (Long, () -> Unit) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryInitialize: (Long) -> Unit = {},
    private val publishInteraction: (Boolean) -> Unit = {},
) {
    private class Candidate(
        val packageName: String, val windowId: Int, val token: Long, val previous: Boolean,
        var attempts: Int = 0, var lastAttemptAt: Long = 0, var inFlight: Boolean = false,
        var scheduled: Boolean = false, var confirmed: Boolean = false,
    )
    private var connected = false
    private var interactive = true
    private var generation = 0L
    private var currentCandidate: Candidate? = null
    private var initializationRetries = 0
    private var initializationScheduled = false
    private var initializationConfirmed = false
    private var paused: Boolean? = null
    private var interactionPaused: Boolean? = null
    private val cache = LinkedHashMap<String, Boolean>(16, .75f, true)

    fun connect(interactive: Boolean): Long {
        connected = true
        this.interactive = interactive
        resetCandidate()
        generation++
        protectInteraction(true)
        pause(interactive, updateInteraction = interactive)
        return generation
    }

    fun candidate(packageName: String, windowId: Int) {
        if (!connected || !interactive) return
        val current = currentCandidate
        if (current?.packageName == packageName && current.windowId == windowId &&
            (current.confirmed || current.inFlight || current.scheduled || clock() - current.lastAttemptAt < 1_000)) return
        val previous = paused ?: true
        val token = ++generation
        val next = Candidate(packageName, windowId, token, previous)
        currentCandidate = next
        // 已确认的非游戏换 Activity 不制造取消/恢复冷却，仍异步核验窗口归属。
        if (previous || cache[packageName] != false) pause(true)
        if (packageName.isBlank()) return
        request(next)
    }

    private fun request(candidate: Candidate) {
        if (!current(candidate) || candidate.inFlight || candidate.confirmed) return
        candidate.scheduled = false
        candidate.inFlight = true
        candidate.attempts++
        candidate.lastAttemptAt = clock()
        query(candidate.packageName, candidate.windowId, cache[candidate.packageName]) { result ->
            if (!current(candidate) || !candidate.inFlight) return@query
            candidate.inFlight = false
            if (result.window == GameForegroundWindow.Overlay ||
                result.window == GameForegroundWindow.Application && result.gaming != null) {
                candidate.confirmed = true
                apply(candidate.packageName, result, candidate.previous)
            } else if (candidate.attempts <= 2) {
                candidate.scheduled = true
                schedule(if (candidate.attempts == 1) 250 else 750) { request(candidate) }
            }
        }
    }

    fun initialize(token: Long, packageName: String?, result: GameForegroundResult) {
        if (!connected || !interactive || token != generation || initializationConfirmed) return
        if (packageName != null && result.window == GameForegroundWindow.Application && result.gaming != null) {
            initializationConfirmed = true
            apply(packageName, result, true)
        } else if (!initializationScheduled && initializationRetries < 2) {
            initializationScheduled = true
            initializationRetries++
            schedule(if (initializationRetries == 1) 250 else 750) {
                if (connected && interactive && token == generation && !initializationConfirmed) {
                    initializationScheduled = false
                    retryInitialize(token)
                }
            }
        }
    }

    fun screen(interactive: Boolean): Long {
        if (!connected || this.interactive == interactive) return generation
        this.interactive = interactive
        resetCandidate()
        generation++
        // 息屏可以补传，但不能把游戏中的红包交互也放行。
        pause(interactive, updateInteraction = interactive)
        return generation
    }

    fun disconnect() {
        connected = false
        generation++
        resetCandidate()
        pause(true)
    }

    private fun current(candidate: Candidate) = connected && interactive &&
        candidate.token == generation && currentCandidate === candidate

    private fun resetCandidate() {
        currentCandidate = null
        initializationRetries = 0
        initializationScheduled = false
        initializationConfirmed = false
    }

    private fun apply(packageName: String, result: GameForegroundResult, previous: Boolean) {
        when (result.window) {
            GameForegroundWindow.Application -> result.gaming?.let { gaming ->
                cache[packageName] = gaming
                while (cache.size > cacheLimit.coerceAtLeast(1)) cache.remove(cache.keys.first())
                pause(gaming)
            }
            GameForegroundWindow.Overlay -> pause(previous)
            GameForegroundWindow.Unknown -> Unit
        }
    }

    private fun protectInteraction(value: Boolean) {
        if (interactionPaused == value) return
        interactionPaused = value
        publishInteraction(value)
    }

    private fun pause(value: Boolean, updateInteraction: Boolean = true) {
        if (updateInteraction) protectInteraction(value)
        if (paused == value) return
        paused = value
        publish(value)
    }
}

@Suppress("DEPRECATION")
internal fun declaredGame(packageName: String, category: Int, flags: Int): Boolean =
    packageName == "com.tencent.tmgp.sgame" || packageName == "com.kidsgame.forest.debug" ||
        category == ApplicationInfo.CATEGORY_GAME || flags and ApplicationInfo.FLAG_IS_GAME != 0

object GameForegroundMonitor {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    fun connect(service: AccessibilityService) = onMain {
        if (session?.service === service) return@onMain
        session?.close()
        Session(service).also { session = it; it.start() }
    }

    fun event(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val windowId = event.windowId
        val className = event.className?.toString().orEmpty()
        if (systemOverlay(packageName) || className.endsWith("SoftInputWindow") ||
            className.endsWith("InputMethodService")) return
        onMain { session?.state?.candidate(packageName, windowId) }
    }

    fun disconnect(service: AccessibilityService) = onMain {
        if (session?.service === service) {
            session?.close()
            session = null
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun systemOverlay(packageName: String) = packageName == "com.android.systemui" || packageName == "android"

    private class Session(val service: AccessibilityService) {
        private val context = service.applicationContext
        // 最多保留一个待执行候选，切应用时不积压窗口读取或 PackageManager 请求。
        private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "game-foreground").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
            ThreadPoolExecutor.DiscardOldestPolicy())
        private var registered = false
        private var closed = false
        val state = GameForegroundState({ packageName, windowId, cached, done ->
            worker.execute {
                val result = resolve(packageName, windowId, cached)
                main.post { if (!closed && session === this) done(result) }
            }
        }, { paused ->
            GameWorkRuntime.setGaming(paused)
            if (BuildConfig.DEBUG) runCatching {
                context.getSharedPreferences("game-work-probe", Context.MODE_PRIVATE).edit()
                    .putBoolean("paused", paused).putLong("updated_at", System.currentTimeMillis()).apply()
            }
        }, schedule = { delay, action ->
            main.postAtTime({ if (!closed && session === this) action() }, this, SystemClock.uptimeMillis() + delay)
        }, retryInitialize = ::initialize,
            publishInteraction = com.yuyan.imemodule.data.redpacket.GroupRedPacketAssistant::protectForeground)
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (closed) return
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> state.screen(false)
                    Intent.ACTION_SCREEN_ON -> initialize(state.screen(true))
                }
            }
        }

        @Suppress("DEPRECATION")
        fun start() {
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
            try {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(receiver, filter)
                registered = true
            } catch (_: RuntimeException) { /* 无广播时保持前台事件驱动，不推断屏幕状态。 */ }
            val interactive = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
            val token = state.connect(interactive)
            if (interactive) initialize(token)
        }

        @Suppress("DEPRECATION")
        private fun initialize(token: Long) {
            worker.execute {
                val root = runCatching { service.rootInActiveWindow }.getOrNull()
                val identity = try { root?.let { it.packageName?.toString() to it.windowId } }
                    finally { root?.recycle() }
                val packageName = identity?.first
                val result = if (packageName != null) resolve(packageName, identity?.second ?: -1, null)
                    else GameForegroundResult(GameForegroundWindow.Unknown)
                main.post { if (!closed && session === this) state.initialize(token, packageName, result) }
            }
        }

        @Suppress("DEPRECATION")
        private fun resolve(packageName: String, windowId: Int, cached: Boolean?): GameForegroundResult {
            if (systemOverlay(packageName)) return GameForegroundResult(GameForegroundWindow.Overlay)
            val windows = runCatching { service.windows }.getOrNull()
                ?: return GameForegroundResult(GameForegroundWindow.Unknown)
            val kind = try {
                val window = windows.firstOrNull { it.id == windowId }
                when {
                    window == null -> GameForegroundWindow.Unknown
                    window.type != AccessibilityWindowInfo.TYPE_APPLICATION -> GameForegroundWindow.Overlay
                    !window.isActive && !window.isFocused -> GameForegroundWindow.Unknown
                    else -> GameForegroundWindow.Application
                }
            } finally { windows.forEach { it.recycle() } }
            if (kind != GameForegroundWindow.Application) return GameForegroundResult(kind)
            val gaming = cached ?: runCatching {
                if (declaredGame(packageName, ApplicationInfo.CATEGORY_UNDEFINED, 0)) true
                else context.packageManager.getApplicationInfo(packageName, 0).let { info ->
                    declaredGame(packageName, if (Build.VERSION.SDK_INT >= 26) info.category else ApplicationInfo.CATEGORY_UNDEFINED, info.flags)
                }
            }.getOrNull()
            return GameForegroundResult(kind, gaming)
        }

        fun close() {
            closed = true
            main.removeCallbacksAndMessages(this)
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            registered = false
            worker.shutdownNow()
            state.disconnect()
        }
    }
}
