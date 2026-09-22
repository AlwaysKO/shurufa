package com.yuyan.imemodule.data.capture.net

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CaptureUploaderPreparationTest {
    private fun fixture(block: suspend (CaptureDatabase, File) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val dir = createTempDirectory("image-preparation-").toFile()
        try { block(db, dir) } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun pausedPreparationDoesNotReadMissingFileOrMarkFailure() = fixture { db, dir ->
        val dao = db.captureDao()
        val asset = PendingAssetEntity("a".repeat(64), File(dir, "missing").path, "image/webp", null, 10, 10)
        dao.insertPendingAsset(asset)
        val uploader = CaptureUploader(dao, CaptureApi("http://localhost", "device", enqueue = { _, _ -> error("paused") }),
            assetFile = { File(dir, it) }, beginPreparation = { null })
        assertEquals(UploadRunResult(0, 0), uploader.runOnce(1000))
        assertEquals(0, dao.findPendingAsset(asset.sha256)!!.attempts)
        assertEquals(0L, uploader.internalFailureCount.get())
    }

    @Test fun releasesPreparationBeforePausingNextAssetAndCanResume() = fixture { db, dir ->
        val dao = db.captureDao()
        for (id in listOf("a", "b")) {
            val file = File(dir, id).apply { writeText(id) }
            dao.insertPendingAsset(PendingAssetEntity(id.repeat(64), file.path, "image/webp", null, 10, 10))
        }
        var paused = false
        var held = false
        var closed = 0
        var uploads = 0
        val uploader = CaptureUploader(dao, CaptureApi("http://localhost", "device", enqueue = { _, _ ->
            assertTrue(held); uploads++; true
        }), assetFile = { File(dir, it) }, beginPreparation = {
            if (paused) null else { held = true; Closeable { held = false; paused = true; closed++ } }
        })
        assertEquals(UploadRunResult(1, 0), uploader.runOnce(1000))
        assertFalse(held)
        assertEquals(1, dao.dueAssets(1000, 200).size)
        paused = false
        assertEquals(UploadRunResult(1, 0), uploader.runOnce(1000))
        assertEquals(2, uploads)
        assertEquals(2, closed)
    }

    @Test fun preparationFailureReleasesPermitAndKeepsNormalBackoff() = fixture { db, dir ->
        val dao = db.captureDao()
        val asset = PendingAssetEntity("a".repeat(64), File(dir, "missing").path, "image/webp", null, 10, 10)
        dao.insertPendingAsset(asset)
        var closed = 0
        val uploader = CaptureUploader(dao, CaptureApi("http://localhost", "device"), assetFile = { File(dir, it) },
            beginPreparation = { Closeable { closed++ } })
        assertEquals(UploadRunResult(1, 1), uploader.runOnce(1000))
        assertEquals(1, closed)
        assertEquals(1, dao.findPendingAsset(asset.sha256)!!.attempts)
    }
}
