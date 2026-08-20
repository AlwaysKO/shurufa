package com.yuyan.imemodule.service.capture

import android.app.Notification
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yuyan.imemodule.data.capture.CaptureCoordinator
import com.yuyan.imemodule.data.capture.RoomCaptureOutboxStore
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.net.CaptureUploader
import com.yuyan.imemodule.data.capture.notification.NotificationMediaImporter
import com.yuyan.imemodule.data.capture.notification.NotificationParser
import com.yuyan.imemodule.data.capture.notification.NotificationSnapshot
import com.yuyan.imemodule.data.collect.DataCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PassiveNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parser = NotificationParser()
    private var database: CaptureDatabase? = null
    private var coordinator: CaptureCoordinator? = null
    private var mediaImporter: NotificationMediaImporter? = null

    override fun onCreate() {
        super.onCreate()
        val captureDatabase = CaptureDatabase.create(applicationContext)
        database = captureDatabase
        mediaImporter = NotificationMediaImporter(applicationContext)
        coordinator = CaptureCoordinator(
            store = RoomCaptureOutboxStore(captureDatabase.captureDao()),
            deviceId = { DataCollector.deviceId(applicationContext) },
            wakeUploader = CaptureUploader::wake,
        )
        CaptureUploader.start(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName !in SUPPORTED_PACKAGES) return
        val activeCoordinator = coordinator ?: return
        val importer = mediaImporter ?: return
        scope.launch {
            val latestMessage = findLatestMessage(notification.notification)
            val mediaUri = latestMessage?.dataUri ?: findFallbackMediaUri(notification.notification)
            val mediaReadable = mediaUri?.let(importer::canRead) == true
            val parsed = parser.parse(
                NotificationSnapshot(
                    packageName = notification.packageName,
                    notificationKey = notification.key,
                    title = notification.notification.extras
                        .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                        ?.toString()
                        ?: notification.notification.extras
                            .getCharSequence(Notification.EXTRA_TITLE)
                            ?.toString(),
                    text = latestMessage?.text?.toString()
                        ?: notification.notification.extras
                            .getCharSequence(Notification.EXTRA_TEXT)
                            ?.toString()
                        ?: notification.notification.extras
                            .getCharSequence(Notification.EXTRA_BIG_TEXT)
                            ?.toString(),
                    postedAtMillis = notification.postTime,
                    isGroupConversation = notification.notification.extras
                        .getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
                    senderName = latestMessage?.let(::messageSender),
                    mediaUri = mediaUri?.toString(),
                    mediaUriReadable = mediaReadable,
                ),
            ) ?: return@launch

            val asset = parsed.mediaUri?.let(Uri::parse)?.let { importer.importImage(it) }
            val message = if (parsed.mediaUri != null && asset == null) {
                parsed.message.copy(
                    metadata = parsed.message.metadata + ("asset_capture_failed" to "true"),
                )
            } else {
                parsed.message
            }
            activeCoordinator.captureParsed(
                conversation = parsed.conversation,
                messages = listOf(message),
                pendingAssetsByMessage = asset?.let { mapOf(0 to it) }.orEmpty(),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        database?.close()
        database = null
        coordinator = null
        mediaImporter = null
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
        val SUPPORTED_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.ss.android.ugc.aweme",
        )
    }
}
