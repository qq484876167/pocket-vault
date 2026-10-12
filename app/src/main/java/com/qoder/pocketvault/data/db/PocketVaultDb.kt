package com.qoder.pocketvault.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(
    entities = [
        VaultEntry::class, ReaderProgress::class, ReaderBookmark::class,
        MediaPosition::class, PdfBookmark::class, ReadingStat::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PocketVaultDb : RoomDatabase() {

    abstract fun vaultDao(): VaultDao

    abstract fun readingProgressDao(): ReadingProgressDao

    abstract fun readingBookmarkDao(): ReadingBookmarkDao

    abstract fun mediaPositionDao(): MediaPositionDao

    abstract fun pdfBookmarkDao(): PdfBookmarkDao

    abstract fun readingStatDao(): ReadingStatDao

    /**
     * 索引行被**真正销毁**（彻底删除、清空回收站、过期清理、对账剔除）时，把五张记账表里
     * 属于这些条目的行一起清掉。
     *
     * 只在这几处调用：条目进回收站时绝不能清账（还原后进度/书签要还在）。
     * 私密数据也一样：文件删了却留着一行"某人某天读到第几页"，等于给备份和取证留尾巴。
     */
    suspend fun purgeAccountRows(ids: List<Long>) {
        if (ids.isEmpty()) return
        val distinct = ids.distinct()
        readingProgressDao().forgetEntries(distinct)
        readingBookmarkDao().forgetEntries(distinct)
        mediaPositionDao().forgetEntries(distinct)
        pdfBookmarkDao().forgetEntries(distinct)
        readingStatDao().forgetEntries(distinct)
    }

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

        /**
         * 只加三张记账小表（播放位置 / PDF 书签 / 阅读统计），entries 一个字节都不碰。
         *
         * 这里的 DDL 必须与 Room 由实体生成的 `createSql` **逐字一致**（列顺序、类型、
         * NOT NULL、主键），否则 Room 在升级完做 schema 校验时会直接抛异常，
         * 用户表现为"更新后打不开"。scratch/statprobe.py 会把这段 SQL 和
         * app/schemas/...4.json 里的 createSql 逐条对账，改实体必须同步跑它。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_positions` (" +
                        "`entryId` INTEGER NOT NULL, `positionMillis` INTEGER NOT NULL, " +
                        "`durationMillis` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`entryId`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pdf_bookmarks` (" +
                        "`entryId` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`entryId`, `pageIndex`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_stats` (" +
                        "`entryId` INTEGER NOT NULL, `day` TEXT NOT NULL, " +
                        "`seconds` INTEGER NOT NULL, `blocks` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`entryId`, `day`))"
                )
            }
        }

        fun build(context: Context): PocketVaultDb =
            Room.databaseBuilder(context.applicationContext, PocketVaultDb::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
