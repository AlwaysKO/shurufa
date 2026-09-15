package com.yuyan.imemodule.data.capture.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seen_message")
data class SeenMessageEntity(
    @PrimaryKey val fingerprint: String,
    val firstSeenAt: Long,
)

@Entity(tableName = "pending_asset")
data class PendingAssetEntity(
    @PrimaryKey val sha256: String,
    val localPath: String,
    val mimeType: String,
    val perceptualHash: String?,
    val width: Int?,
    val height: Int?,
    val attempts: Int = 0,
    val nextRetryAt: Long = 0,
)

@Entity(tableName = "pending_message")
data class PendingMessageEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val conversationKey: String,
    val payloadJson: String,
    val requiredAssetHashesJson: String,
    val attempts: Int = 0,
    val nextRetryAt: Long = 0,
)
