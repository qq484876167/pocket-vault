package com.qoder.pocketvault.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(
    entities = [VaultEntry::class, ReaderProgress::class, ReaderBookmark::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PocketVaultDb : RoomDatabase() {

    abstract fun vaultDao(): VaultDao

    abstract fun readingProgressDao(): ReadingProgressDao

    abstract fun readingBookmarkDao(): ReadingBookmarkDao

    companion object {
        const val DB_NAME = "vault.db"

        /** 只加阅读进度表，不动 entries：索引数据绝不能因为升级被清空。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_progress` (" +
                        "`entryId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                        "`paragraphIndex` INTEGER NOT NULL, `fontSize` REAL NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`entryId`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_bookmarks` (" +
                        "`entryId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                        "`paragraphIndex` INTEGER NOT NULL, `label` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`entryId`, `chapterIndex`, `paragraphIndex`))"
                )
            }
        }

        fun build(context: Context): PocketVaultDb =
            Room.databaseBuilder(context.applicationContext, PocketVaultDb::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
