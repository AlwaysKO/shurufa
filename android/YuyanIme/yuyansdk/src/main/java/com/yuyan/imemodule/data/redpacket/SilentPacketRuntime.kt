package com.yuyan.imemodule.data.redpacket

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.redpacket.silent.ISilentPacketService
import kotlinx.coroutines.*
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicLong
import com.yuyan.imemodule.data.redpacket.SilentPacketDiagnosticEvent.*

/** 通知只保留有限内存队列；副屏图像只交给端侧识别，不进入采集或上传链。 */
internal object SilentPacketRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private var context: Context? = null
    @Volatile private var service: ISilentPacketService? = null
    @Volatile private var report = "静默红包未连接"
        set(value) {
            field = value
            if (com.yuyan.imemodule.BuildConfig.DEBUG) android.util.Log.i("YuyanSilent", value)
        }
    @Volatile private var userReport = "{\"users\":[]}"
    @Volatile private var lastOutcome = ""
    private val diagnostics = SilentPacketDiagnostics()
    private var binding = false
    private var installedListeners = false
    private var job: Job? = null
    private var stopsInFlight = 0
    private var active: PacketRequest? = null
    @Volatile private var activeCardCommitted = false
    private val recent = PacketDeduplicator()
    private val startGate = SilentPacketStartGate()
    private var pendingDiagnostic: Runnable? = null
    private var retryScheduled = false
    private val retry = Runnable { retryScheduled = false; drain() }
    private val queued = ArrayDeque<PacketRequest>()
    private var args: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            service = ISilentPacketService.Stub.asInterface(binder)
            diagnostics.record(SERVICE_CONNECTED)
            report = "静默服务已连接，等待白名单群红包"
            refreshStatus()
            drain()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            binding = false
            service = null
            diagnostics.record(SERVICE_DISCONNECTED)
            stop("静默服务已断开，请检查 Shizuku")
        }
    }
    private val received = Shizuku.OnBinderReceivedListener {
        context?.takeIf { SilentPacketSettings.mode(it) != "OFF" }?.let { connect(it) }
    }
    private val dead = Shizuku.OnBinderDeadListener {
        diagnostics.record(SHIZUKU_DISCONNECTED)
        service = null
        main.post { binding = false; stop("Shizuku 已断开；副屏由硬截止回收") }
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == 9042 && grant == PackageManager.PERMISSION_GRANTED) context?.let { connect(it) }
        else if (code == 9042) { diagnostics.record(WAITING_PERMISSIONS); report = "Shizuku 未获授权" }
    }

    fun status(): String = (if (lastOutcome.isEmpty()) report else "$report\n最近红包任务（历史结果）：$lastOutcome") +
        if (com.yuyan.imemodule.BuildConfig.DEBUG) "\n" + diagnostics.snapshot().render() else ""
    fun users(): String = userReport

    /** 用户主动执行的只读检查；不发送通知入口、不点击；识别仅在内存进行，不输出聊天文字。 */
    fun diagnose(value: Context, userId: Int) {
        if (Build.VERSION.SDK_INT < 35 || userId < 0) { report = "请先选择有效微信账号"; return }
        val app = value.applicationContext
        main.post {
            if (job != null || stopsInFlight > 0) { report = "请先停止当前会话"; return@post }
            if (service == null) { report = "请先连接 Shizuku"; return@post }
            pendingDiagnostic?.let(main::removeCallbacks)
            val expected = generation.get()
            val deadline = SystemClock.uptimeMillis() + 2_000
            startGate.defer(SystemClock.uptimeMillis())
            report = "等待主屏操作稳定后只读检查"
            val pending = object : Runnable {
                override fun run() {
                    if (pendingDiagnostic !== this) return
                    if (generation.get() != expected) { pendingDiagnostic = null; drain(); return }
                    val now = SystemClock.uptimeMillis()
                    if (now >= deadline) {
                        pendingDiagnostic = null
                        report = "主屏持续变化，自检尚未启动"
                        drain()
                        return
                    }
                    val wait = startGate.remaining(now)
                    if (wait > 0) { main.postDelayed(this, minOf(wait, deadline - now)); return }
                    pendingDiagnostic = null
                    startDiagnosis(app, userId, expected)
                }
            }
            pendingDiagnostic = pending
            main.postDelayed(pending, startGate.remaining(SystemClock.uptimeMillis()))
        }
    }

    private fun startDiagnosis(app: Context, userId: Int, expected: Long) {
        if (generation.get() != expected) return
        if (job != null || stopsInFlight > 0) { report = "请先停止当前会话"; return }
        val current = service ?: run { report = "请先连接 Shizuku"; return }
        val deadline = SystemClock.uptimeMillis() + 17_000
        val safe = { SystemClock.uptimeMillis() < deadline && generation.get() == expected && GroupRedPacketAssistant.isInteractionAllowed() &&
            ImageUploadRuntime.isBackgroundWorkAllowed() && PacketServiceState.accessibilityConnected &&
            app.getSystemService(PowerManager::class.java).isInteractive &&
            !app.getSystemService(KeyguardManager::class.java).isDeviceLocked }
        report = "正在只读检查微信副屏"
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!safe()) { report = "等待无障碍连接、输入空闲并离开游戏后重试"; return@launch }
                val result = JSONObject(current.start(userId))
                if (result.optString("state") != "RUNNING") { report = result.toString(); return@launch }
                val reader = SilentPacketReader()
                val checks = mutableListOf<SilentPacketReadDiagnostic>()
                val prepareStarted = SystemClock.uptimeMillis()
                var prepareMs = 0L
                try {
                    if (!reader.prepare(safe)) { report = "识别预热被保护中止，已停止"; return@launch }
                    prepareMs = SystemClock.uptimeMillis() - prepareStarted
                    repeat(2) {
                        var frame: SilentPacketFrame? = null
                        for (attempt in 0 until 10) {
                            if (!safe()) break
                            delay(250)
                            if (!safe()) break
                            frame = captureFrame(current, safe) ?: continue
                            break
                        }
                        frame?.let { checks += inspectSilentPacketFrame(it, safe, { image -> reader.read(image, safe) }) }
                    }
                } finally { reader.close() }
                report = if (checks.size == 2 && safe()) {
                    "副屏只读诊断：微信位于副屏 ${result.optInt("displayId")}，主屏焦点为0；预热${prepareMs}ms；" +
                        checks.mapIndexed { index, check ->
                            "OCR${index + 1}=${check.result.name}，识别${check.elapsedMs}ms，帧龄${check.frameAgeMs}ms"
                        }.joinToString("；") + "；正在释放副屏"
                } else "副屏画面尚未验证，已停止"
            } catch (_: Exception) { report = "只读自检失败，已请求回收副屏" }
            finally {
                val cleaned = runCatching {
                    current.stop()
                    JSONObject(current.status()).optInt("displayId", 0) == -1
                }.getOrDefault(false)
                if (report.startsWith("副屏只读诊断")) report = report.replace("正在释放副屏",
                    if (cleaned) "副屏已释放" else "已请求释放副屏，清理结果未确认")
            }
        }
        job = task
        // OCR等待不可取消；主线程独立请求释放副屏，输入Bitmap仍等OCR回调后回收。
        val timeout = Runnable {
            if (job === task && !task.isCompleted && generation.get() == expected)
                stop("只读诊断已超时，已请求停止副屏")
        }
        task.invokeOnCompletion {
            main.removeCallbacks(timeout)
            main.post { if (job === task) { job = null; drain() } }
        }
        main.postDelayed(timeout, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
        task.start()
    }

    fun connect(value: Context, requestPermission: Boolean = false) {
        if (Build.VERSION.SDK_INT < 35) { report = "静默副屏需要 Android 15 或以上"; return }
        val app = value.applicationContext
        main.post {
            context = app
            if (!installedListeners) {
                installedListeners = true
                Shizuku.addBinderReceivedListenerSticky(received)
                Shizuku.addBinderDeadListener(dead)
                Shizuku.addRequestPermissionResultListener(permission)
            }
            runCatching {
                if (!Shizuku.pingBinder()) { diagnostics.record(WAITING_CONNECTION); report = "请先通过无线调试启动 Shizuku"; return@runCatching }
                if (Shizuku.getUid() != 2000) { report = "需要 Shizuku 无线调试 shell 模式"; return@runCatching }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    diagnostics.record(WAITING_PERMISSIONS)
                    report = "请授权 Shizuku"
                    if (requestPermission) Shizuku.requestPermission(9042)
                    return@runCatching
                }
                if (service != null) { refreshStatus(); return@runCatching }
                if (binding) return@runCatching
                binding = true
                val next = Shizuku.UserServiceArgs(ComponentName(app.packageName,
                    "com.yuyan.redpacket.silent.SilentPacketService"))
                    .daemon(true).processNameSuffix("silent_packet").debuggable(false).version(3)
                args = next
                Shizuku.bindUserService(next, connection)
                report = "正在连接静默服务"
                main.postDelayed({
                    if (binding && service == null) { binding = false; report = "连接超时，请检查 Shizuku 并重新连接" }
                }, 10_000)
            }.onFailure { binding = false; report = "无法连接 Shizuku 静默服务" }
        }
    }

    fun refreshStatus() {
        val current = service ?: run { report = "请先连接 Shizuku 静默服务"; return }
        scope.launch {
            runCatching {
                val state = current.status()
                val users = current.users()
                if (service === current) { report = state; userReport = users }
            }.onFailure { if (service === current) report = "服务状态查询失败，请重新连接" }
        }
    }

    fun disconnect() {
        stop("静默服务已断开")
        main.post {
            if (Build.VERSION.SDK_INT >= 35) runCatching {
                args?.let { Shizuku.unbindUserService(it, connection, true) }
            }
            service = null; binding = false
        }
    }

    fun stop(reason: String) {
        generation.incrementAndGet()
        report = reason
        main.post {
            pendingDiagnostic?.let(main::removeCallbacks)
            pendingDiagnostic = null
            active = null
            queued.clear()
            clearRetry()
            job?.cancel()
            service?.let { stopBackend(it) }
        }
    }

    fun protectForeground(protected: Boolean) {
        diagnostics.record(if (protected) FOREGROUND_PROTECTED else FOREGROUND_READY)
        if (protected) {
            generation.incrementAndGet()
            main.post {
                clearRetry()
                active?.takeIf { !activeCardCommitted }?.let { packet ->
                    if (queued.none { it.candidate.id == packet.candidate.id }) {
                        if (queued.size >= 4) queued.removeFirst()
                        queued.addFirst(packet.copy(deferredByGame = true))
                    }
                }
                job?.cancel()
                service?.let { stopBackend(it) }
                report = "游戏或前台未确认，静默红包暂停"
            }
        } else main.post {
            if (report == "游戏或前台未确认，静默红包暂停") report = "前台保护已解除，等待群红包"
            scheduleDrain(3_100)
        }
    }

    fun event(event: AccessibilityEvent) {
        if (silentPacketUserAction(event.eventType)) {
            startGate.defer(SystemClock.uptimeMillis())
            // 副屏事件已在无障碍服务入口隔离；这里只处理主屏操作信号。
            if (job?.isActive == true) {
                diagnostics.record(INPUT_STOPPED)
                diagnostics.record(when (event.eventType) {
                    AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> INPUT_STOPPED_TOUCH
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> INPUT_STOPPED_TEXT
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> INPUT_STOPPED_CLICK
                    else -> INPUT_STOPPED_SCROLL
                })
                stop("检测到主屏操作或界面滚动，静默红包已停止")
            }
        }
    }

    fun notification(value: Context, sbn: StatusBarNotification?) {
        if (Build.VERSION.SDK_INT < 35 || sbn == null || sbn.packageName != PACKET_WECHAT) return
        val app = value.applicationContext
        val selectedUser = SilentPacketSettings.userId(app)
        val noticeUser = sbn.uid / 100_000
        // OEM 通知归属只用于诊断；授权仍按 UID 和后端 PendingIntent 创建者分别校验。
        @Suppress("DEPRECATION")
        val sbnUser = sbn.userId
        val intentUser = sbn.notification.contentIntent?.creatorUid?.div(100_000) ?: -1
        diagnostics.record(NOTICE_SEEN, selectedUser, noticeUser, sbnUser, intentUser)
        if (SilentPacketSettings.mode(app) == "OFF") { diagnostics.record(MODE_OFF); return }
        val packet = runCatching { packetNotification(sbn, System.currentTimeMillis()) }.getOrNull()
            ?: run { diagnostics.record(PARSE_REJECTED); return }
        diagnostics.record(PARSED)
        if (noticeUser != selectedUser) { diagnostics.record(PROFILE_REJECTED); return }
        diagnostics.record(PROFILE_MATCHED)
        if (!silentPacketEligible(SilentPacketSettings.mode(app), selectedUser,
                noticeUser, SilentPacketSettings.groups(app), packet.candidate)) {
            diagnostics.record(GROUP_REJECTED); return
        }
        diagnostics.record(ELIGIBLE)
        main.post {
            context = app
            if (!recent.accept(packet.candidate.id)) { diagnostics.record(DEDUPLICATED); return@post }
            if (queued.size >= 4) { queued.removeFirst(); diagnostics.record(QUEUE_DROPPED) }
            queued.addLast(packet.copy(deferredByGame = !GroupRedPacketAssistant.isInteractionAllowed()))
            diagnostics.record(QUEUED)
            startGate.defer(SystemClock.uptimeMillis())
            connect(app)
            drain()
        }
    }

    private fun allowed(app: Context, expected: Long): Boolean = generation.get() == expected &&
        SilentPacketSettings.mode(app) != "OFF" && GroupRedPacketAssistant.isInteractionAllowed() &&
        PacketServiceState.accessibilityConnected && PacketServiceState.notificationConnected &&
        ImageUploadRuntime.isBackgroundWorkAllowed() &&
        app.getSystemService(PowerManager::class.java).isInteractive &&
        !app.getSystemService(KeyguardManager::class.java).isDeviceLocked

    /** 新任务必须等所有停止请求返回，防止旧任务的异步 stop 关闭新副屏。 */
    private fun stopBackend(current: ISilentPacketService) {
        val stop = scope.launch(start = CoroutineStart.LAZY) { runCatching { current.stop() } }
        stopsInFlight++
        stop.invokeOnCompletion {
            main.post { stopsInFlight--; drain() }
        }
        stop.start()
    }

    private fun clearRetry() { main.removeCallbacks(retry); retryScheduled = false }
    private fun scheduleDrain(delay: Long) {
        if (retryScheduled) return
        retryScheduled = true
        main.postDelayed(retry, delay)
    }

    /** 主线程串行出队；没有红包时不轮询，不创建副屏。 */
    private fun drain() {
        val app = context ?: return
        queued.removeAll {
            (!it.isValidAt(System.currentTimeMillis())).also { stale -> if (stale) diagnostics.record(TASK_STALE) }
        }
        if (queued.isEmpty()) clearRetry()
        if (job != null || pendingDiagnostic != null || stopsInFlight > 0 || queued.isEmpty() || SilentPacketSettings.mode(app) == "OFF") return
        val quietWait = startGate.remaining(SystemClock.uptimeMillis())
        if (quietWait > 0) { scheduleDrain(quietWait); return }
        if (!GroupRedPacketAssistant.isInteractionAllowed()) { diagnostics.record(WAITING_PROTECTION); return }
        // 断线后只由 BinderReceived/用户重新连接恢复，不每秒唤醒等待 Shizuku。
        val current = service ?: run { diagnostics.record(WAITING_CONNECTION); return }
        if (!ImageUploadRuntime.isInputIdle()) {
            diagnostics.record(WAITING_INPUT)
            scheduleDrain(1_000); return
        }
        while (queued.isNotEmpty()) {
            val request = queued.removeFirst()
            if (!request.isValidAt(System.currentTimeMillis())) { diagnostics.record(TASK_STALE); continue }
            if (request.candidate.chatName !in SilentPacketSettings.groups(app)) {
                diagnostics.record(TASK_GROUP_REJECTED); continue
            }
            val expected = generation.get()
            active = request
            activeCardCommitted = false
            val task = scope.launch(start = CoroutineStart.LAZY) {
                var outcome = "静默任务未开始"
                fun finish(label: String, event: SilentPacketDiagnosticEvent) {
                    outcome = label
                    report = label
                    diagnostics.record(event)
                }
                diagnostics.record(TASK_STARTED)
                try {
                    if (!allowed(app, expected)) {
                        finish("输入、前台或权限保护，任务未开始", TASK_BLOCKED); return@launch
                    }
                    val prefs = app.getSharedPreferences("silent_packet_receipts", Context.MODE_PRIVATE)
                    val ledger = SilentPacketLedger(prefs.getString("attempted", "").orEmpty()) {
                        prefs.edit().putString("attempted", it).commit()
                    }
                    if (ledger.contains(request.candidate.id)) {
                        finish("已处理或结果未确认，跳过重复通知", TASK_DUPLICATE); return@launch
                    }
                    val start = JSONObject(current.start(SilentPacketSettings.userId(app)))
                    diagnostics.startedBackend(start.optString("state"))
                    if (start.optString("state") != "RUNNING") {
                        finish("副屏启动失败：${start.optString("state")}", TASK_START_FAILED); return@launch
                    }
                    if (!allowed(app, expected)) {
                        finish("副屏已启动，但客户端保护已中止任务", TASK_START_INVALIDATED); return@launch
                    }
                    if (!current.launch(request.intent)) {
                        finish("通知未能定向副屏，已停止", TASK_LAUNCH_FAILED); return@launch
                    }
                    if (!allowed(app, expected)) {
                        finish("通知已定向副屏，但客户端保护已中止任务", TASK_LAUNCH_INVALIDATED); return@launch
                    }
                    val backend = object : SilentPacketBackend {
                        override fun capture(): SilentPacketFrame? {
                            return captureFrame(current) { allowed(app, expected) }
                        }
                        override fun tap(x: Int, y: Int, frameId: Long): Boolean {
                            if (!allowed(app, expected)) return false
                            com.yuyan.imemodule.data.collect.HumanInteractionRuntime.suppressAutomatedGesture(app)
                            return current.tap(x, y, frameId)
                        }
                        override fun back(): Boolean {
                            if (!allowed(app, expected)) return false
                            com.yuyan.imemodule.data.collect.HumanInteractionRuntime.suppressAutomatedGesture(app)
                            return current.back()
                        }
                    }
                    val mode = if (SilentPacketSettings.mode(app) == "AUTO") SilentPacketMode.AUTO else SilentPacketMode.PROBE
                    val result = SilentPacketEngine.run(request, backend, { allowed(app, expected) }, mode,
                        beforeCard = {
                            (allowed(app, expected) && ledger.reserve(request.candidate.id, System.currentTimeMillis()))
                                .also { if (it) activeCardCommitted = true }
                        }, onTrace = diagnostics::engine)
                    outcome = resultLabel(result)
                    report = outcome
                    diagnostics.finished(result)
                } catch (_: CancellationException) { finish("静默任务已取消", TASK_CANCELLED) }
                catch (_: Exception) { finish("静默任务异常，结果未确认；不会自动重放", TASK_EXCEPTION) }
                finally {
                    lastOutcome = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(java.util.Date()) + " " + outcome
                    runCatching { current.stop() }
                }
            }
            job = task
            task.invokeOnCompletion {
                main.post { if (job === task) { job = null; active = null; drain() } }
            }
            task.start()
            return
        }
    }

    /** 图片与时间、帧ID在服务内同一次捕获中生成，不再追加一次昂贵的状态验证RPC。 */
    private fun captureFrame(current: ISilentPacketService, permitted: () -> Boolean): SilentPacketFrame? {
        if (!permitted()) return null
        val bundle = current.captureFrame() ?: return null
        @Suppress("DEPRECATION")
        val bitmap = bundle.getParcelable<android.graphics.Bitmap>("bitmap") ?: return null
        return try {
            if (!permitted()) { bitmap.recycle(); null }
            else SilentPacketFrame(bitmap, bundle.getLong("frameId"), bundle.getLong("capturedAtUptime", -1),
                bundle.getInt("displayId", -1))
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }

    private fun resultLabel(result: String): String = when (result) {
        "claimed" -> "已识别领取成功"
        "expired" -> "红包已过期"
        "exhausted" -> "红包已被领完"
        "probe_candidate" -> "只读识别到群红包，未点击"
        "probe_no_candidate" -> "只读未识别到可领取红包"
        "protected" -> "输入或前台保护，已停止"
        "mismatch" -> "通知与副屏群名不一致，已停止"
        "stale_request" -> "通知已超时，已跳过"
        "duplicate" -> "重复任务或去重保存失败，已停止"
        "off" -> "静默红包已关闭"
        else -> "领取结果未确认，不会自动重放"
    }
}
