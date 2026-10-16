package com.yuyan.imemodule.data.collect

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal data class DiagnosticLease(val wall: Long, val elapsed: Long, val boot: Int, val duration: Long) {
    fun active(nowWall: Long, nowElapsed: Long, currentBoot: Int): Boolean =
        duration in 1..CollectionDiagnostics.MAX_DURATION_MS && currentBoot == boot &&
            nowWall - wall in 0 until duration && nowElapsed - elapsed in 0 until duration
}

/** 默认关闭。排障会话与业务授权/队列分开，到期不再写；不使用Alarm或WakeLock。 */
internal object CollectionDiagnostics {
    const val MAX_DURATION_MS = 60 * 60 * 1000L
    private const val PREFS = "collection_diagnostic_session"
    private val stateLock = Any()
    private val files = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequence = AtomicLong(System.currentTimeMillis())
    @Volatile private var app: Context? = null
    private var lease: DiagnosticLease? = null
    private var currentToken = 0L
    private var consentEpoch = 0L
    private var expiration: Job? = null

    private fun boot(context: Context): Int = if (Build.VERSION.SDK_INT >= 24)
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
        else -1

    fun bind(context: Context) {
        val application = context.applicationContext
        synchronized(stateLock) {
            if (app === application) return
            app = application
            scope.launch { com.yuyan.imemodule.utils.LogRetention.prune(application) }
            expiration?.cancel()
            val prefs = application.getSharedPreferences(PREFS, 0)
            val currentBoot = boot(application)
            lease = DiagnosticLease(prefs.getLong("wall", 0), prefs.getLong("elapsed", 0),
                prefs.getInt("boot", -1), prefs.getLong("duration", 0)).takeIf {
                // 无boot证据的旧系统只在本进程临时开启，重启进程不恢复日志。
                currentBoot >= 0 && CollectionConsent.enabled(application) &&
                    it.active(System.currentTimeMillis(), SystemClock.elapsedRealtime(), currentBoot)
            }
            currentToken = sequence.updateAndGet { maxOf(it + 1, prefs.getLong("token", 0) + 1) }
            consentEpoch = CollectionConsent.epoch
            if (lease == null) scope.launch { clearIfInactive(application) }
            else scheduleExpiration(application, currentToken, requireNotNull(lease))
        }
    }

    private fun activeLocked(context: Context): Boolean = lease?.let {
        CollectionConsent.enabled(context) && consentEpoch == CollectionConsent.epoch &&
            it.active(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(context))
    } == true

    private fun resetTransient() {
        CaptureHistory.cancelPending()
        ChatCaptureDiagnostics.resetDiagnostics()
    }

    private fun deactivateLocked() {
        lease = null
        currentToken = sequence.incrementAndGet()
        expiration?.cancel(); expiration = null
        resetTransient()
    }

    fun token(context: Context): Long? {
        bind(context)
        return synchronized(stateLock) {
            if (activeLocked(context.applicationContext)) currentToken
            else {
                if (lease != null) {
                    deactivateLocked()
                    scope.launch { clearIfInactive(context.applicationContext) }
                }
                null
            }
        }
    }
    fun enabled(context: Context): Boolean = token(context) != null
    fun enabled(): Boolean = app?.let(::enabled) == true

    /** 只用于短小的内存入队，不能在此块中读写文件或联网。 */
    fun whenEnabled(context: Context, block: (Long) -> Unit) {
        bind(context)
        synchronized(stateLock) { token(context)?.let(block) }
    }

    suspend fun writeIfCurrent(context: Context, expectedToken: Long, block: () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            files.withLock {
                if (token(context) != expectedToken) false
                else { block(); true }
            }
        }
    fun persist(context: Context, expectedToken: Long, block: () -> Unit) {
        scope.launch { writeIfCurrent(context.applicationContext, expectedToken, block) }
    }

    suspend fun begin(context: Context, durationMs: Long = MAX_DURATION_MS) = withContext(Dispatchers.IO) {
        val application = context.applicationContext
        bind(application)
        require(durationMs in 1..MAX_DURATION_MS)
        check(CollectionConsent.enabled(application))
        files.withLock {
            synchronized(stateLock) { deactivateLocked() }
            deleteDiagnosticFiles(application)
            val window = DiagnosticLease(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(application), durationMs)
            synchronized(stateLock) {
                currentToken = sequence.incrementAndGet()
                consentEpoch = CollectionConsent.epoch
                check(application.getSharedPreferences(PREFS, 0).edit().putLong("wall", window.wall)
                    .putLong("elapsed", window.elapsed).putInt("boot", window.boot).putLong("duration", durationMs)
                    .putLong("token", currentToken).commit())
                lease = window
                scheduleExpiration(application, currentToken, window)
            }
        }
    }

    suspend fun end(context: Context) {
        val application = context.applicationContext
        bind(application)
        synchronized(stateLock) { deactivateLocked() }
        withContext(NonCancellable) { clearIfInactive(application) }
    }
    fun stop(context: Context) {
        bind(context)
        synchronized(stateLock) { deactivateLocked() }
        scope.launch { clearIfInactive(context.applicationContext) }
    }

    suspend fun clearIfInactive(context: Context) = withContext(Dispatchers.IO) {
        val application = context.applicationContext
        bind(application)
        files.withLock {
            if (token(application) != null) return@withLock
            synchronized(stateLock) { resetTransient() }
            deleteDiagnosticFiles(application)
            application.getSharedPreferences(PREFS, 0).edit().clear().commit()
        }
    }

    private fun scheduleExpiration(context: Context, expectedToken: Long, window: DiagnosticLease) {
        expiration?.cancel()
        expiration = scope.launch {
            delay((window.duration - (SystemClock.elapsedRealtime() - window.elapsed)).coerceAtLeast(1))
            synchronized(stateLock) {
                if (currentToken == expectedToken && !activeLocked(context)) {
                    expiration = null
                    deactivateLocked()
                }
            }
            clearIfInactive(context)
        }
    }

    private fun deleteDiagnosticFiles(context: Context) {
        for (suffix in listOf("", "-wal", "-shm", "-journal"))
            File(context.noBackupFilesDir, "capture_history.db$suffix").delete()
        for (name in listOf("chat_capture_diagnostics_v1", "douyin_capture_diagnostics", "capture_diagnostics")) {
            // 清缓存实例后删文件，避免旧SharedPreferences实例把删除前内容写回来。
            context.getSharedPreferences(name, 0).edit().clear().commit()
            if (Build.VERSION.SDK_INT >= 24) context.deleteSharedPreferences(name)
            else for (suffix in listOf(".xml", ".xml.bak")) File(context.applicationInfo.dataDir, "shared_prefs/$name$suffix").delete()
        }
    }
}
