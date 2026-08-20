package com.yuyan.imemodule.data.capture.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Dao
abstract class CaptureDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSeen(entity: SeenMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSeen(entities: List<SeenMessageEntity>): List<Long>

    @Query("SELECT * FROM seen_message WHERE fingerprint = :fingerprint")
    abstract suspend fun findSeen(fingerprint: String): SeenMessageEntity?

    @Query("SELECT COUNT(*) FROM seen_message")
    abstract suspend fun countSeen(): Int

    @Query("SELECT fingerprint FROM seen_message ORDER BY firstSeenAt ASC, fingerprint ASC LIMIT :limit")
    protected abstract suspend fun oldestSeenFingerprints(limit: Int): List<String>

    @Query("DELETE FROM seen_message WHERE fingerprint IN (:fingerprints)")
    protected abstract suspend fun deleteSeen(fingerprints: List<String>)

    @Transaction
    open suspend fun trimSeen(maxCount: Int = 50_000) {
        val overflow = countSeen() - maxCount.coerceAtLeast(0)
        if (overflow > 0) deleteSeen(oldestSeenFingerprints(overflow))
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPendingAsset(entity: PendingAssetEntity): Long

    @Query("SELECT * FROM pending_asset WHERE sha256 = :sha256")
    abstract suspend fun findPendingAsset(sha256: String): PendingAssetEntity?

    @Query("SELECT * FROM pending_asset WHERE nextRetryAt <= :now ORDER BY nextRetryAt ASC, sha256 ASC LIMIT :limit")
    abstract suspend fun dueAssets(now: Long, limit: Int): List<PendingAssetEntity>

    @Query("SELECT sha256 FROM pending_asset")
    protected abstract suspend fun pendingAssetHashes(): List<String>

    @Query("DELETE FROM pending_asset WHERE sha256 = :sha256")
    abstract suspend fun deletePendingAsset(sha256: String)

    @Query("UPDATE pending_asset SET attempts = :attempts, nextRetryAt = :nextRetryAt WHERE sha256 = :sha256")
    abstract suspend fun updateAssetRetry(sha256: String, attempts: Int, nextRetryAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPendingMessage(entity: PendingMessageEntity): Long

    @Transaction
    open suspend fun enqueueIfNew(
        seenMessage: SeenMessageEntity,
        pendingMessage: PendingMessageEntity,
        pendingAssets: List<PendingAssetEntity> = emptyList(),
    ): Boolean {
        if (insertSeen(seenMessage) == -1L) return false
        pendingAssets.forEach { insertPendingAsset(it) }
        check(insertPendingMessage(pendingMessage) != -1L) { "pending message id already exists" }
        return true
    }

    @Query("SELECT * FROM pending_message WHERE nextRetryAt <= :now ORDER BY nextRetryAt ASC, id ASC")
    protected abstract suspend fun dueMessages(now: Long): List<PendingMessageEntity>

    @Query("SELECT COUNT(*) > 0 FROM pending_message WHERE id = :id")
    abstract suspend fun hasPendingMessage(id: String): Boolean

    @Query("DELETE FROM pending_message WHERE id = :id")
    abstract suspend fun confirmMessageUploaded(id: String)

    @Query("DELETE FROM pending_message WHERE id IN (:ids)")
    abstract suspend fun confirmMessagesUploaded(ids: List<String>)

    @Query("UPDATE pending_message SET attempts = :attempts, nextRetryAt = :nextRetryAt WHERE id = :id")
    abstract suspend fun updateMessageRetry(id: String, attempts: Int, nextRetryAt: Long)

    @Transaction
    open suspend fun readyMessages(now: Long, limit: Int): List<PendingMessageEntity> {
        if (limit <= 0) return emptyList()
        val pending = pendingAssetHashes().toHashSet()
        return dueMessages(now).asSequence()
            .filter { message -> requiredAssets(message)?.none(pending::contains) == true }
            .take(limit)
            .toList()
    }

    private fun requiredAssets(message: PendingMessageEntity): List<String>? = try {
        Json.decodeFromString(message.requiredAssetHashesJson)
    } catch (_: Exception) {
        null
    }
}
