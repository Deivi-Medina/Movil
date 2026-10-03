package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DownloadedSongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SongReviewEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AudiophilesDatabase : RoomDatabase() {
    abstract fun downloadedSongDao(): DownloadedSongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun songReviewDao(): SongReviewDao

    companion object {
        @Volatile
        private var INSTANCE: AudiophilesDatabase? = null

        fun getInstance(context: Context): AudiophilesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AudiophilesDatabase::class.java,
                    "audiophiles_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
