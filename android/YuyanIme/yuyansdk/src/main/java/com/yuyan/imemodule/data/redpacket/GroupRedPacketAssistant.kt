package com.yuyan.imemodule.data.redpacket

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import com.yuyan.imemodule.data.capture.ui.IntRect
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import java.util.UUID
import java.util.concurrent.Executors

/** 主线程串行、小队列、短时任务；不依赖聊天采集同意，不保存通知正文。 */
internal object GroupRedPacketAssistant {
    @Volatile private var gameProtected = false
    @Volatile private var gameResumeAt = 0L

    /** Monitor与操作入口均在主线程；保护先关闭，旧读取只能释放自身资源。 */
    fun protectForeground(protected: Boolean) {
        if (gameProtected == protected) return
        if (protected) {
            gameProtected = true
            val deferred = queued.toMutableList()
            request?.takeIf { clickedCard == null }?.let { active ->
                if (deferred.none { it.candidate.id == active.candidate.id }) deferred.add(0, active)
            }
            cancel("游戏或前台确认期间暂停红包", false)
            deferred.filter { it.isValidAt(System.currentTimeMillis()) }
                .takeLast(4).forEach { queued.addLast(it.copy(deferredByGame = true)) }
        } else {
            gameResumeAt = now() + 3_000
            gameProtected = false
            if (service?.let(PacketSettings::enabled) == true) {
                status = "等待前台稳定后恢复红包"
                schedule(3_000)
            }
        }
    }

    fun isInteractionAllowed(): Boolean = !gameProtected && now() >= gameResumeAt
    private val handler = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private val reader = Executors.newSingleThreadExecutor()
    private data class ReadResult(val native: PacketWindow, val visual: PacketVisualSnapshot?,
        val needsVisual: Boolean, val attemptedVisual: Boolean)
    private var reading = false
    private fun finishReading() {
        reading = false
        if (isInteractionAllowed() && queued.isNotEmpty() && service?.let(PacketSettings::enabled) == true) schedule()
    }
    private val visualReader = PacketVisualReader()
    private var nextVisualAt = 0L
    private var visualReadRetries = 0
    private val visualReceipt = PacketVisualClickReceipt()
    private val initialLayout = PacketInitialLayout()
    private var visualReceiptUntil = 0L
    private var generation = 0L
    private val recentNotices = PacketDeduplicator()
    private val recentCards = VisiblePacketAttempts()
    private val retryBudget = PacketRetryBudget()
    private var clickedCard: String? = null
    private val queued = ArrayDeque<PacketRequest>()
    private var request: PacketRequest? = null
    private var flow: PacketFlow? = null
    private var wakeToken: String? = null
    private var wokeScreen = false
    private var enteredWechat = false
    private var activityName = ""
    private val clickReceipt = PacketClickReceipt()
    private var waitingForTarget = false
    private var pausedUntil = 0L
    private var ownBackIdleUntil = 0L
    @Volatile var status: String = "等待群红包"
        private set(value) { field = value; service?.let { PacketProbe.state(it, value) } }

    private var tickScheduled = false
    private val tick = Runnable {
        tickScheduled = false
        runCatching { process() }.onFailure { cancel("界面暂不可操作", true) }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && !gameProtected) cancel("屏幕已关闭", false)
        }
    }

    fun connect(value: AccessibilityService) {
        disconnect(service)
        service = value
        PacketServiceState.accessibilityConnected = true
        ContextCompat.registerReceiver(value, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (PacketSettings.enabled(value)) schedule()
    }

    fun disconnect(value: AccessibilityService?) {
        if (value == null || service !== value) return
        cancel("无障碍服务未连接", false)
        runCatching { value.unregisterReceiver(screenReceiver) }
        service = null
        PacketServiceState.accessibilityConnected = false
    }

    fun notification(value: StatusBarNotification?) {
        val sbn = value ?: return
        if (Build.VERSION.SDK_INT < 28 || sbn.packageName != PACKET_WECHAT) return
        handler.post {
            val observer = service ?: return@post
            if (!PacketSettings.enabled(observer)) return@post
            runCatching {
                val parsed = packetNotification(sbn, System.currentTimeMillis())
                PacketProbe.notification(observer, parsed != null)
                val next = parsed ?: return@runCatching
                if (!recentNotices.accept(next.candidate.id)) return@runCatching
                if (queued.size >= 4) queued.removeFirst()
                queued.addLast(next.copy(deferredByGame = !isInteractionAllowed()))
                if (flow == null) startNext()
            }.onFailure { status = "通知暂不可识别" }
        }
    }

    private fun startNext() {
        val observer = service ?: return
        if (!isInteractionAllowed()) return
        if (now() < ownBackIdleUntil) { schedule(); return }
        if (!PacketSettings.enabled(observer) || !ImageUploadRuntime.isInputIdle() || now() < pausedUntil) {
            cancel("等待输入空闲", false); return
        }
        while (queued.isNotEmpty()) {
            val next = queued.removeFirst()
            if (!next.isValidAt(System.currentTimeMillis())) continue
            val keyguard = observer.getSystemService(KeyguardManager::class.java)
            if (keyguard.isDeviceLocked) { cancel("需先手动解锁", false); return }
            generation++
            request = next
            clickedCard = null
            flow = PacketFlow(next.candidate, now())
            enteredWechat = false
            waitingForTarget = true
            activityName = ""
            val power = observer.getSystemService(PowerManager::class.java)
            wokeScreen = wokeScreen || !power.isInteractive
            status = "正在打开群红包"
            if (!power.isInteractive || keyguard.isKeyguardLocked) {
                val token = UUID.randomUUID().toString()
                wakeToken = token
                runCatching {
                    observer.startActivity(Intent(observer, PacketWakeActivity::class.java)
                        .putExtra("packet_token", token)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY))
                }.onFailure { cancel("系统未允许亮屏", true) }
            } else launchNotification()
            schedule()
            return
        }
        finishSession()
    }

    fun hasWakeRequest(token: String?): Boolean = token != null && token == wakeToken && flow != null &&
        isInteractionAllowed() && service?.let(PacketSettings::enabled) == true

    fun wakeReady(token: String?) {
        if (!hasWakeRequest(token)) return
        wakeToken = null
        launchNotification()
    }

    fun wakeFailed(token: String?) {
        if (hasWakeRequest(token)) cancel("亮屏或解除锁屏未完成", true)
    }

    private fun launchNotification() {
        if (!isInteractionAllowed()) return
        val observer = service ?: return
        val next = request ?: return
        runCatching {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            expectNavigation(observer)
            next.intent.send(observer, 0, null, null, null, null, options.toBundle())
        }.onFailure { failure ->
            if (failure is PendingIntent.CanceledException) {
                generation++
                request = null
                flow = null
                wakeToken = null
                waitingForTarget = false
                initialLayout.clear()
                status = "通知入口已失效，尝试下一条"
                if (queued.isNotEmpty()) schedule() else finishSession()
            } else cancel("通知入口被系统拦截", true)
        }
    }

    fun event(event: AccessibilityEvent) {
        val observer = service ?: return
        if (Build.VERSION.SDK_INT < 28 || !PacketSettings.enabled(observer)) return
        if (gameProtected) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { generation++; visualReadRetries = 0 }
        val pkg = event.packageName?.toString()
        if (flow != null || queued.isNotEmpty()) {
            val autoClick = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val source = event.source
                try {
                    val native = clickReceipt.consume(event.windowId, source?.let(::packetNodeIdentity), now())
                    val box = source?.let { Rect().also(it::getBoundsInScreen) }
                    val visual = visualReceipt.consume(event.windowId,
                        box?.let { IntRect(it.left, it.top, it.right, it.bottom) }, now())
                    native || visual
                }
                finally { @Suppress("DEPRECATION") source?.recycle() }
            } else false
            val automaticLayout = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && pkg == PACKET_WECHAT &&
                initialLayout.consume(event.windowId, now(), event.scrollX == 0 && event.scrollY == 0 &&
                    event.scrollDeltaX == 0 && event.scrollDeltaY == 0)
            val userAction = event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) ||
                (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && !automaticLayout) ||
                (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !autoClick)
            if (userAction || (isInteractionAllowed() && (enteredWechat || (flow == null && queued.isNotEmpty())) &&
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    pkg != PACKET_WECHAT)) {
                PacketProbe.event(observer, event.eventType, event.windowId, pkg == PACKET_WECHAT, now() - (visualReceiptUntil - 600))
                PacketProbe.scroll(observer,event)
                cancel("检测到操作，已停止", false); return
            }
        }
        if (pkg != PACKET_WECHAT) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) activityName = event.className?.toString().orEmpty()
        if (flow == null && event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) return
        schedule()
    }

    private fun schedule(delayMillis: Long = 250) {
        if (gameProtected) return
        if (!tickScheduled) {
            tickScheduled = true
            handler.postDelayed(tick, maxOf(delayMillis, gameResumeAt - now()))
        }
    }

    private fun process() {
        if (!isInteractionAllowed()) return
        if (status == "等待前台稳定后恢复红包") status = "等待群红包"
        if (reading) return
        val observer = service ?: return
        if (!PacketSettings.enabled(observer) || now() < pausedUntil) {
            if (flow != null || queued.isNotEmpty()) cancel("输入中或已暂停", false)
            return
        }
        if (!ImageUploadRuntime.isInputIdle()) {
            // 自动返回键也经过 IME 的三秒输入优先冷却；等待冷却，绝不绕过它截图或点击。
            if (now() < ownBackIdleUntil) schedule()
            else if (flow != null || queued.isNotEmpty()) cancel("输入中，已停止", false)
            return
        }
        if (flow == null && queued.isNotEmpty()) { startNext(); return }
        val keyguard = observer.getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked) { cancel("需先手动解锁", false); return }
        if (flow?.step(PacketPage(), now()) == PacketAction.Stop) { cancel("本次任务超时", true); return }
        if (wakeToken != null) { schedule(); return }
        val root = observer.rootInActiveWindow
        if (root == null) {
            PacketProbe.record(observer, 0, -1, 0, 0, null)
            if (flow != null) schedule(); return
        }
        if (root.packageName?.toString() != PACKET_WECHAT) {
            @Suppress("DEPRECATION") root.recycle()
            if (enteredWechat) cancel("已离开微信", false)
            else if (flow != null) schedule()
            return
        }
        if (!observer.getSystemService(PowerManager::class.java).isInteractive || keyguard.isKeyguardLocked) {
            @Suppress("DEPRECATION") root.recycle()
            if (flow != null) schedule()
            return
        }
        val readGeneration = generation
        val readActivity = activityName
        val windowId = root.windowId
        val windowBounds = android.graphics.Rect().also(root::getBoundsInScreen)
        reading = true
        reader.execute {
            val result = runCatching {
                check(isInteractionAllowed())
                val native = PacketWindow(root, readActivity)
                val needsVisual = native.nodeCount <= 1 || (native.page.chatName == null &&
                    !native.page.verifiedGroupDetails && native.page.openButton == null && native.page.result == null)
                val attemptedVisual = needsVisual && Build.VERSION.SDK_INT >= 30 && now() >= nextVisualAt
                val visual = if (attemptedVisual) {
                    nextVisualAt = now() + 750
                    runCatching { visualReader.read(observer, windowId) }.getOrNull()
                } else null
                ReadResult(native, visual, needsVisual, attemptedVisual)
            }
            handler.post {
                finishReading()
                val window = result.getOrNull()?.native
                val visual = result.getOrNull()?.visual
                if (window == null) {
                    @Suppress("DEPRECATION") root.recycle()
                    if (generation == readGeneration) cancel("界面暂不可读取", true)
                    else if (flow != null) schedule()
                    return@post
                }
                window.use {
                    PacketProbe.record(observer, window.nodeCount, windowId, windowBounds.width(), windowBounds.height(), visual?.match?.page ?: window.page, visual != null)
                    if (service !== observer || generation != readGeneration || !PacketSettings.enabled(observer) ||
                        !isInteractionAllowed() || !ImageUploadRuntime.isInputIdle() || now() < pausedUntil) {
                        if (service === observer && PacketSettings.enabled(observer)) schedule()
                        return@post
                    }
                    val active = observer.rootInActiveWindow
                    val sameWindow = active?.windowId == windowId && active.packageName?.toString() == PACKET_WECHAT
                    @Suppress("DEPRECATION") active?.recycle()
                    if (!sameWindow) { if (flow != null) schedule(); return@post }
                    if (result.getOrNull()?.needsVisual == true && visual == null && Build.VERSION.SDK_INT >= 30) {
                        if (result.getOrNull()?.attemptedVisual == false || ++visualReadRetries < 3) schedule()
                        else if (flow != null) cancel("截图暂不可用", true)
                        return@post
                    }
                    if (visual != null) visualReadRetries = 0
                    runCatching { consumeWindow(observer, window, visual) }.onFailure { cancel("界面暂不可操作", true) }
                }
            }
        }
    }

    private fun consumeWindow(observer: AccessibilityService, window: PacketWindow, visual: PacketVisualSnapshot?) {
        if (!isInteractionAllowed()) return
        val page = visual?.match?.page ?: window.page
        val signatures = visual?.signatures ?: window.cardSignatures
        when (visualReceipt.verify(page, now())) {
            PacketVisualReceiptCheck.Wait -> { schedule(); return }
            PacketVisualReceiptCheck.Stop -> { cancel("点击后页面未确认，已停止", false); return }
            PacketVisualReceiptCheck.Ready -> Unit
        }
        if (waitingForTarget) {
            if (page.chatName != flow?.candidate?.chatName) { schedule(); return }
            waitingForTarget = false
        }
        enteredWechat = true
        page.chatName?.let { recentCards.observe(it, signatures.values) }
        if (flow == null) {
            if (!page.groupChat || page.chatName == null || page.cards.isEmpty()) return
            val signature = signatures[page.cards.last()] ?: return
            if (!recentCards.accept(page.chatName, signature)) return
            if (!retryBudget.allow(signature, now())) {
                recentCards.completed(page.chatName, signature)
                return
            }
            flow = PacketFlow(PacketCandidate(signature, page.chatName, true), now())
            status = "正在处理当前群红包"
        }
        when (val action = flow!!.step(page, now())) {
            PacketAction.Wait -> schedule()
            PacketAction.Stop -> cancel("会话不匹配或无法确认群聊", false)
            PacketAction.Back -> {
                (visual?.windowId ?: window.clickIdentity("root")?.first)?.let { initialLayout.expect(it, now()) }
                if (observer.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                    ownBackIdleUntil = now() + 3500
                    schedule()
                }
                else cancel("无法返回原会话", true)
            }
            is PacketAction.Click -> {
                if (flow?.canClick(now()) != true) { cancel("本次任务超时", true); return }
                signatures[action.id]?.let {
                    recentCards.accept(flow!!.candidate.chatName, it)
                }
                if (visual != null) confirmVisualClick(observer, visual, action.id)
                else {
                    window.clickIdentity(action.id)?.let {
                        clickReceipt.expect(it.first, it.second, now())
                        initialLayout.expect(it.first, now())
                    }
                    if (window.click(action.id)) {
                        signatures[action.id]?.let { clickedCard = it }
                        schedule()
                    } else cancel("红包控件不可点击", true)
                }
            }
            is PacketAction.Finish -> {
                status = action.result
                clickedCard?.let { recentCards.completed(flow!!.candidate.chatName, it) }
                clickedCard = null
                flow = null
                request = null
                wakeToken = null
                // 仅从已确认的红包结果页返回，不在未知页面自动按返回。
                expectNavigation(observer)
                if (observer.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) ownBackIdleUntil = now() + 3500
                if (queued.isNotEmpty()) startNext() else finishSession()
            }
        }
    }

    private fun confirmVisualClick(observer: AccessibilityService, first: PacketVisualSnapshot, id: String) {
        if (!isInteractionAllowed()) return
        val expectedGeneration = generation
        reading = true
        reader.execute {
            val second = runCatching { visualReader.read(observer, first.windowId) }.getOrNull()
            handler.post {
                finishReading()
                if (service !== observer || generation != expectedGeneration || flow == null || !isInteractionAllowed()) {
                    if (flow != null) schedule(); return@post
                }
                if (flow?.canClick(now()) != true) { cancel("本次任务超时", true); return@post }
                val target = second?.let { confirmedPacketTarget(first, it, id, now()) }
                val active = observer.rootInActiveWindow
                val sameWindow = active?.windowId == first.windowId && active.packageName?.toString() == PACKET_WECHAT
                @Suppress("DEPRECATION") active?.recycle()
                val keyguard = observer.getSystemService(KeyguardManager::class.java)
                if (target == null || !sameWindow || !PacketSettings.enabled(observer) ||
                    !ImageUploadRuntime.isInputIdle() || keyguard.isKeyguardLocked || keyguard.isDeviceLocked ||
                    !observer.getSystemService(PowerManager::class.java).isInteractive) {
                    cancel("画面变化，已停止点击", true); return@post
                }
                val path = Path().apply { moveTo((target.left+target.right)/2f,(target.top+target.bottom)/2f) }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,60)).build()
                visualReceipt.expect(first.windowId, target, id, now())
                initialLayout.expect(first.windowId, now())
                visualReceiptUntil = now() + 600
                PacketProbe.action(observer, if (id.startsWith("visual:card:")) "card" else id)
                val dispatched = observer.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) { if (flow != null) schedule() }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (generation == expectedGeneration && flow != null) cancel("点击被打断", false)
                    }
                }, handler)
                if (!dispatched) cancel("系统未允许点击", true) else {
                    first.signatures[id]?.let { clickedCard = it }
                    schedule()
                }
            }
        }
    }

    private fun expectNavigation(observer: AccessibilityService) {
        val root = observer.rootInActiveWindow
        root?.windowId?.let { initialLayout.expect(it, now()) }
        @Suppress("DEPRECATION") root?.recycle()
    }

    fun cancel(reason: String, restoreScreen: Boolean = false) {
        generation++
        handler.removeCallbacks(tick)
        tickScheduled = false
        queued.clear()
        flow = null
        request = null
        clickedCard = null
        wakeToken = null
        waitingForTarget = false
        ownBackIdleUntil = 0L
        clickReceipt.clear()
        visualReceipt.clear()
        initialLayout.clear()
        status = reason
        if (!restoreScreen) wokeScreen = false
        finishSession()
        pausedUntil = now() + 3_000
    }

    private fun finishSession() {
        handler.removeCallbacks(tick)
        tickScheduled = false
        val observer = service
        val restore = wokeScreen
        wokeScreen = false
        enteredWechat = false
        if (restore && observer != null && Build.VERSION.SDK_INT >= 28 && isInteractionAllowed()) {
            val root = observer.rootInActiveWindow
            val pkg = root?.packageName?.toString()
            @Suppress("DEPRECATION") root?.recycle()
            if (pkg == PACKET_WECHAT || pkg == observer.packageName) {
                observer.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
    }

    private fun now() = SystemClock.uptimeMillis()
}
