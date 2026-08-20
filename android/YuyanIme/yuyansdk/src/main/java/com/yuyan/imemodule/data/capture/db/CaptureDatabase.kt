package com.yuyan.imemodule.data.capture.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SeenMessageEntity::class,
        PendingAssetEntity::class,
        PendingMessageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao

    companion object {
        fun create(context: Context): CaptureDatabase = Room.databaseBuilder(
            context.applicationContext,
            CaptureDatabase::class.java,
            "capture.db",
        ).build()
    }
}
