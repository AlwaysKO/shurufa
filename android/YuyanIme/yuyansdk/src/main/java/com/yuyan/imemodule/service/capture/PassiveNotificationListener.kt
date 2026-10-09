package com.yuyan.imemodule.service.capture

import android.app.Notification
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yuyan.imemodule.data.capture.CaptureCoordinator
import com.yuyan.imemodule.data.callrecording.WechatCallSignals
import com.yuyan.imemodule.data.capture.CapturePersistResult
import com.yuyan.imemodule.data.capture.RoomCaptureOutboxStore
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.net.CaptureUploader
import com.yuyan.imemodule.data.capture.net.CaptureWorkSignal
import com.yuyan.imemodule.data.capture.notification.NotificationMediaImporter
import com.yuyan.imemodule.data.capture.notification.NotificationEventDeduplicator
import com.yuyan.imemodule.data.capture.notification.NotificationParser
import com.yuyan.imemodule.data.capture.notification.NotificationSnapshot
import com.yuyan.imemodule.data.capture.notification.DeferredNotificationMedia
import com.yuyan.imemodule.data.capture.notification.DeferredMediaStage
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.redpacket.PacketServiceState
import com.yuyan.imemodule.data.redpacket.PacketSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

class PassiveNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaAdmission = Semaphore(32)
    private val mediaDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "notification-media-staging")
            .apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val parser = NotificationParser()
    private val eventDeduplicator = NotificationEventDeduplicator()
    private var database: CaptureDatabase? = null
    private var coordinator: CaptureCoordinator? = null
    private var mediaImporter: NotificationMediaImporter? = null
    private var fallbackStore: NotificationScreenshotFallbackStore? = null
    private var waitingForOpenStore: NotificationScreenshotFallbackStore? = null
    private var packetRebindRequested = false
    private var deferredMedia: DeferredNotificationMedia? = null
    private val deferredMediaSignal = CaptureWorkSignal()

    override fun onListenerConnected() {
        super.onListenerConnected()
        packetRebindRequested = false
        PacketServiceState.notificationConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        WechatCallSignals.clear()
        PacketServiceState.notificationConnected = false
        if (android.os.Build.VERSION.SDK_INT >= 35)
            com.yuyan.imemodule.data.redpacket.SilentPacketRuntime.stop("通知服务已断开")
        val needsPacketListener = PacketSettings.enabled(this) ||
            (android.os.Build.VERSION.SDK_INT >= 35 &&
                com.yuyan.imemodule.data.redpacket.SilentPacketSettings.mode(this) != "OFF")
        if (!packetRebindRequested && needsPacketListener && PacketSettings.hasNotifications(this)) {
            packetRebindRequested = true
            runCatching { requestRebind(ComponentName(this, PassiveNotificationListener::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val captureDatabase = CaptureDatabase.create(applicationContext)
        database = captureDatabase
        mediaImporter = NotificationMediaImporter(applicationContext)
        deferredMedia = DeferredNotificationMedia(File(filesDir, "notification-media-pending"), yieldAfterChunk = {
            if (!GameWorkRuntime.isBackgroundAllowed()) Thread.sleep(16)
        })
        fallbackStore = NotificationScreenshotFallbackStore(applicationContext)
        waitingForOpenStore = NotificationScreenshotFallbackStore(
            applicationContext,
            preferencesName = WAITING_FOR_OPEN_PREFERENCES,
        )
        coordinator = CaptureCoordinator(
            store = RoomCaptureOutboxStore(captureDatabase.captureDao()),
            deviceId = { DataCollector.deviceId(applicationContext) },
            wakeUploader = CaptureUploader::wake,
            captureAllowed = { CollectionConsent.enabled(applicationContext) },
        )
        CaptureUploader.start(applicationContext)
        scope.launch(mediaDispatcher) {
            while (isActive) {
                val processed = try { processDeferredMedia() }
                catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    false // 输入优先暂停不等于服务关闭；已暂存任务保持可恢复。
                } catch (_: Exception) { false }
                val pending = runCatching { deferredMedia?.hasPending() == true }.getOrDefault(true)
                deferredMediaSignal.awaitNext(processed && ImageUploadRuntime.isBackgroundWorkAllowed(), hasPending = pending)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (android.os.Build.VERSION.SDK_INT >= 35)
            com.yuyan.imemodule.data.redpacket.SilentPacketRuntime.notification(this, sbn)
        com.yuyan.imemodule.data.redpacket.GroupRedPacketAssistant.notification(sbn)
        sbn?.takeIf { it.packageName == "com.tencent.mm" }?.let { callNotification ->
            val extras = callNotification.notification.extras
            WechatCallSignals.notification(
                packageName = callNotification.packageName,
                key = callNotification.key,
                ongoing = callNotification.isOngoing,
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                isMessagingStyle = extras.containsKey(Notification.EXTRA_MESSAGES) ||
                    extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$MessagingStyle",
                postedAtMillis = callNotification.postTime,
            )
        }
        if (!CollectionConsent.enabled(this)) return
        val notification = sbn ?: return
        if (notification.packageName !in SUPPORTED_PACKAGES) return
        val activeCoordinator = coordinator ?: return
        val importer = mediaImporter ?: return
        val latestMessage = findLatestMessage(notification.notification)
        val summaryText = notification.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            ?: notification.notification.extras
                .getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.toString()
        val preliminarySnapshot = NotificationSnapshot(
            packageName = notification.packageName,
            notificationKey = notification.key,
            title = notification.notification.extras
                .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()
                ?: notification.notification.extras
                    .getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString(),
            text = latestMessage?.text?.toString() ?: summaryText,
            postedAtMillis = notification.postTime,
            isGroupConversation = notification.notification.extras
                .getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            senderName = latestMessage?.let(::messageSender),
            summaryText = summaryText,
            isMessagingStyle = latestMessage != null,
            sourceMessageTimestampMillis = latestMessage?.timestamp,
            stableConversationId = trustedConversationShortcut(notification),
            profileKey = notification.user.toString(),
        )
        if (parser.shouldIgnore(preliminarySnapshot)) return
        if (parser.requiresScreenshotFallback(preliminarySnapshot)) {
            if (eventDeduplicator.shouldAccept(preliminarySnapshot)) {
                waitingForOpenStore?.offerReplacingNotification(
                    NotificationScreenshotFallbackRequest(
                        notificationKey = notification.key,
                        postedAtMillis = notification.postTime,
                        packageName = notification.packageName,
                    ),
                )
            }
            return
        }
        val mediaUri = latestMessage?.dataUri ?: findFallbackMediaUri(notification.notification)
        val needsMediaSlot = mediaUri != null
        if (needsMediaSlot && !mediaAdmission.tryAcquire()) {
            recordMediaStagingFailure("admission_full")
            return
        }
        val job = scope.launch {
            if (!CollectionConsent.enabled(this@PassiveNotificationListener)) return@launch
            val mediaReadable = mediaUri?.let(importer::canRead) == true
            val snapshot = preliminarySnapshot.copy(
                mediaUri = mediaUri?.toString(),
                mediaUriReadable = mediaReadable,
            )
            if (!eventDeduplicator.shouldAccept(snapshot)) return@launch

            if (parser.requiresMediaScreenshotFallback(snapshot)) {
                val request = NotificationScreenshotFallbackRequest(
                    notificationKey = notification.key,
                    postedAtMillis = notification.postTime,
                    packageName = notification.packageName,
                )
                fallbackStore?.offer(request)
                NotificationScreenshotFallbackBridge.request(request)
            }
            val parsed = parser.parse(snapshot) ?: return@launch

            if (!CollectionConsent.enabled(this@PassiveNotificationListener) || !CollectionConsent.allowsText(parsed.message.text)) return@launch
            if (parsed.mediaUri != null) {
                val staged = withContext(mediaDispatcher) {
                    if (!CollectionConsent.enabled(this@PassiveNotificationListener)) return@withContext null
                    deferredMedia?.stage(snapshot) { contentResolver.openInputStream(Uri.parse(parsed.mediaUri)) }
                }
                if (staged == DeferredMediaStage.Stored || staged == DeferredMediaStage.AlreadyStored) {
                    deferredMediaSignal.wake()
                    return@launch
                }
                // 拒绝新任务不删除旧待办，也不让通知去重阻止原 URI 再次保存。
                eventDeduplicator.remove(snapshot.notificationKey)
                val reason = (staged as? DeferredMediaStage.Rejected)?.reason ?: "service_stopped"
                recordMediaStagingFailure(reason)
                activeCoordinator.captureParsed(parsed.conversation, listOf(parsed.message.copy(
                    metadata = parsed.message.metadata + ("asset_capture_deferred" to reason),
                )))
                return@launch
            }
            activeCoordinator.captureParsed(
                conversation = parsed.conversation,
                messages = listOf(parsed.message),
            )
        }
        if (needsMediaSlot) job.invokeOnCompletion { mediaAdmission.release() }
    }

    private suspend fun processDeferredMedia(): Boolean {
        val importer = mediaImporter ?: return false
        val activeCoordinator = coordinator ?: return false
        val allowed = { CollectionConsent.enabled(this) && ImageUploadRuntime.isBackgroundWorkAllowed() }
        return deferredMedia?.processNext(allowed) { snapshot, source ->
            val parsed = parser.parse(snapshot) ?: return@processNext false
            if (!CollectionConsent.allowsText(parsed.message.text) || !allowed()) return@processNext false
            val asset = importer.importImage(Uri.fromFile(source)) ?: return@processNext false
            if (!allowed()) return@processNext false
            // 只有归一化后的 PNG 与原消息进入既有 outbox，原始编码文件不交给上传器。
            activeCoordinator.captureParsed(parsed.conversation, listOf(parsed.message),
                mapOf(0 to asset)) != CapturePersistResult.FAILED
        } ?: false
    }

    private fun recordMediaStagingFailure(reason: String) {
        val status = getSharedPreferences("notification-media-status", MODE_PRIVATE)
        status.edit().putInt("rejected_count", status.getInt("rejected_count", 0) + 1)
            .putString("last_reason", reason).putLong("last_rejected_at", System.currentTimeMillis()).apply()
        Log.w("NotificationMedia", "Media staging deferred: $reason")
    }

    private fun trustedConversationShortcut(notification: StatusBarNotification): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val ranking = Ranking()
            if (!currentRanking.getRanking(notification.key, ranking) || !ranking.isConversation) null
            else notification.notification.shortcutId?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        // Legacy callbacks provide no removal reason; never infer a chat screenshot request.
        WechatCallSignals.removed(notification.packageName, notification.key, notification.postTime)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        onNotificationRemoved(sbn)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        val notification = sbn ?: return
        WechatCallSignals.removed(notification.packageName, notification.key, notification.postTime)
        eventDeduplicator.remove(notification.key)
        val request = waitingForOpenStore?.takeByNotificationKey(notification.key) ?: return
        if (!shouldArmNotificationScreenshot(reason)) return
        fallbackStore?.offer(request)
        NotificationScreenshotFallbackBridge.request(request)
    }

    override fun onDestroy() {
        WechatCallSignals.clear()
        PacketServiceState.notificationConnected = false
        scope.cancel()
        mediaDispatcher.close()
        database?.close()
        database = null
        coordinator = null
        mediaImporter = null
        deferredMedia = null
        fallbackStore = null
        waitingForOpenStore = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun findLatestMessage(
        notification: Notification,
    ): Notification.MessagingStyle.Message? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val messages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            return Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages).lastOrNull()
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findFallbackMediaUri(notification: Notification): Uri? =
        notification.extras.getParcelable(Notification.EXTRA_AUDIO_CONTENTS_URI) as? Uri

    @Suppress("DEPRECATION")
    private fun messageSender(message: Notification.MessagingStyle.Message): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            message.senderPerson?.name?.toString()
        } else {
            message.sender?.toString()
        }

    private companion object {
        const val WAITING_FOR_OPEN_PREFERENCES = "notification_screenshot_waiting_for_open"
        val SUPPORTED_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.ss.android.ugc.aweme",
        )
    }
}
