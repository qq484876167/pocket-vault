package com.qoder.pocketvault.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/** 每个文件记住上次阅读到的章节与段落，退出重进能回到原位置。 */
@Entity(tableName = "reading_progress", primaryKeys = ["entryId"])
data class ReaderProgress(
    val entryId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val fontSize: Float,
    val updatedAt: Long,
)

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress WHERE entryId = :entryId")
    suspend fun find(entryId: Long): ReaderProgress?

    @Upsert
    suspend fun save(progress: ReaderProgress)

    @Query("DELETE FROM reading_progress WHERE entryId = :entryId")
    suspend fun clear(entryId: Long)
}
