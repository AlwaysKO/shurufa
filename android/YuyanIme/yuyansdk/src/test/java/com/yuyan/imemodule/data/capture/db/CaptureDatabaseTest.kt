package com.yuyan.imemodule.data.capture.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CaptureDatabaseTest {
    private lateinit var database: CaptureDatabase
    private lateinit var dao: CaptureDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.captureDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicateSeenMessageIsIgnored() = runBlocking {
        val seen = SeenMessageEntity("a".repeat(64), firstSeenAt = 1)

        assertTrue(dao.insertSeen(seen) > 0)
        assertEquals(-1L, dao.insertSeen(seen))
        assertEquals(1, dao.countSeen())
    }

    @Test
    fun pendingAssetUsesSha256AsPrimaryKey() = runBlocking {
        val first = PendingAssetEntity("b".repeat(64), "/tmp/first", "image/webp", null, null, null)
        val duplicate = first.copy(localPath = "/tmp/second")

        assertTrue(dao.insertPendingAsset(first) > 0)
        assertEquals(-1L, dao.insertPendingAsset(duplicate))
        assertEquals("/tmp/first", dao.findPendingAsset(first.sha256)?.localPath)
    }

    @Test
    fun messageBecomesReadyOnlyAfterRequiredAssetLeavesOutbox() = runBlocking {
        val hash = "c".repeat(64)
        dao.insertPendingAsset(PendingAssetEntity(hash, "/tmp/c", "image/webp", null, null, null))
        dao.insertPendingMessage(pendingMessage("message-1", hash))

        assertTrue(dao.readyMessages(now = 1_000, limit = 200).isEmpty())
        dao.deletePendingAsset(hash)
        assertEquals(listOf("message-1"), dao.readyMessages(1_000, 200).map { it.id })
    }

    @Test
    fun trimsSeenMessagesToNewestFiftyThousand() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            WITH digits(d) AS (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
            numbers(n) AS (
                SELECT a.d + b.d * 10 + c.d * 100 + d.d * 1000 + e.d * 10000
                FROM digits a, digits b, digits c, digits d, digits e
            )
            INSERT INTO seen_message(fingerprint, firstSeenAt)
            SELECT printf('%064d', n), n FROM numbers WHERE n <= 50000
            """.trimIndent(),
        )

        dao.trimSeen(50_000)

        assertEquals(50_000, dao.countSeen())
        assertNull(dao.findSeen("0".padStart(64, '0')))
        val newestFingerprint = "50000".padStart(64, '0')
        assertEquals(newestFingerprint, dao.findSeen(newestFingerprint)?.fingerprint)
    }

    @Test
    fun uploadConfirmationDeletesOutboxButKeepsSeenFingerprint() = runBlocking {
        val fingerprint = "d".repeat(64)
        dao.insertSeen(SeenMessageEntity(fingerprint, firstSeenAt = 1))
        dao.insertPendingMessage(pendingMessage("message-2"))

        dao.confirmMessageUploaded("message-2")

        assertFalse(dao.hasPendingMessage("message-2"))
        assertEquals(fingerprint, dao.findSeen(fingerprint)?.fingerprint)
    }

    @Test
    fun seenAndPendingMessageAreInsertedAtomically() = runBlocking {
        val pending = pendingMessage("atomic")
        val seen = SeenMessageEntity(pending.fingerprint, firstSeenAt = 1)

        assertTrue(dao.enqueueIfNew(seen, pending))
        assertFalse(dao.enqueueIfNew(seen, pending.copy(id = "another-id")))
        assertEquals(1, dao.countSeen())
        assertTrue(dao.hasPendingMessage("atomic"))
        assertFalse(dao.hasPendingMessage("another-id"))
    }

    @Test
    fun onePendingAssetCanBeReferencedByMultiplePendingMessages() = runBlocking {
        val hash = "a".repeat(64)
        val asset = PendingAssetEntity(
            sha256 = hash,
            localPath = "/tmp/$hash",
            mimeType = "image/png",
            perceptualHash = null,
            width = 60,
            height = 60,
        )
        val first = pendingMessage("asset-message-1", hash).copy(fingerprint = "1".repeat(64))
        val second = pendingMessage("asset-message-2", hash).copy(fingerprint = "2".repeat(64))

        assertTrue(dao.enqueueIfNew(SeenMessageEntity(first.fingerprint, 1), first, listOf(asset)))
        assertTrue(dao.enqueueIfNew(SeenMessageEntity(second.fingerprint, 2), second, listOf(asset)))

        assertEquals(asset, dao.findPendingAsset(hash))
        assertTrue(dao.hasPendingMessage(first.id))
        assertTrue(dao.hasPendingMessage(second.id))
    }

    private fun pendingMessage(id: String, requiredHash: String? = null) = PendingMessageEntity(
        id = id,
        fingerprint = "e".repeat(64),
        conversationKey = "wechat|account|peer",
        payloadJson = "{}",
        requiredAssetHashesJson = requiredHash?.let { "[\"$it\"]" } ?: "[]",
    )
}
