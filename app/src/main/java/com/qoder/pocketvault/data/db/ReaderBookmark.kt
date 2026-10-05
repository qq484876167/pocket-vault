package com.qoder.pocketvault.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/** 书签按「章节 + 章内段落」定位，不存百分比：换字号换行距后依然能回到原位。 */
@Entity(tableName = "reading_bookmarks", primaryKeys = ["entryId", "chapterIndex", "paragraphIndex"])
data class ReaderBookmark(
    val entryId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val label: String,
    val createdAt: Long,
)

@Dao
interface ReadingBookmarkDao {

    @Query("SELECT * FROM reading_bookmarks WHERE entryId = :entryId ORDER BY chapterIndex, paragraphIndex")
    suspend fun forEntry(entryId: Long): List<ReaderBookmark>

    @Upsert
    suspend fun save(bookmark: ReaderBookmark)

    @Query("DELETE FROM reading_bookmarks WHERE entryId = :entryId AND chapterIndex = :chapter AND paragraphIndex = :paragraph")
    suspend fun delete(entryId: Long, chapter: Int, paragraph: Int)

    @Query("DELETE FROM reading_bookmarks WHERE entryId = :entryId")
    suspend fun clear(entryId: Long)
}
