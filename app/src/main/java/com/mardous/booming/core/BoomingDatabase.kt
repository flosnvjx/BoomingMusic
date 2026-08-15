package com.mardous.booming.core

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mardous.booming.data.local.room.*
import com.mardous.booming.data.local.room.ExternalSongEntity

@Database(
    entities = [
        PlaylistEntity::class,
        SongEntity::class,
        HistoryEntity::class,
        PlayCountEntity::class,
        QueueEntity::class,
        InclExclEntity::class,
        LyricsEntity::class,
        ExternalSongEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class BoomingDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun playCountDao(): PlayCountDao
    abstract fun historyDao(): HistoryDao
    abstract fun queueDao(): QueueDao
    abstract fun inclExclDao(): InclExclDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun externalSongDao(): ExternalSongDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PlaylistEntity ADD COLUMN custom_cover_uri TEXT")
                db.execSQL("ALTER TABLE PlaylistEntity ADD COLUMN description TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `QueueEntity` (`id` TEXT NOT NULL, `order` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `CanvasEntity` (`id` INT NOT NULL, `canvas_url` TEXT NOT NULL, `fetch_time` INT NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS CanvasEntity")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `external_songs` (" +
                        "`uri` TEXT NOT NULL, " +
                        "`song_id` INTEGER NOT NULL, " +
                        "`album_id` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`album` TEXT NOT NULL, " +
                        "`album_artist` TEXT, " +
                        "`genre` TEXT, " +
                        "`duration` INTEGER NOT NULL, " +
                        "`size` INTEGER NOT NULL, " +
                        "`date_added` INTEGER NOT NULL, " +
                        "`date_modified` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uri`))"
                )
                db.execSQL("ALTER TABLE SongEntity ADD COLUMN external_uri TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE external_songs ADD COLUMN track INTEGER NOT NULL DEFAULT -1")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Move synthetic external ids from the positive band (which can overlap
                // MediaStore ids on large/restored media DBs) to the disjoint negative band.
                db.execSQL("UPDATE external_songs SET song_id = -song_id, album_id = -album_id")
                db.execSQL("UPDATE SongEntity SET id = -id WHERE external_uri IS NOT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // date_added was stored in milliseconds; MediaStore's DATE_ADDED is in
                // seconds. Convert existing rows so DateAdded sorting is consistent.
                db.execSQL("UPDATE external_songs SET date_added = date_added / 1000")
            }
        }
    }
}