package com.qoder.pocketvault.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * PDF 书签。按「页码」定位，和小说书签按「章 + 段落」定位是同一个思路：
 * 存位置而不是百分比，换设备换屏幕比例都不会漂。
 *
 * [label] 允许空串（界面上显示成"第 N 页"），但不为 null——
 * 与 reading_bookmarks 保持同一形状，省得两套书签各写一份判空。
 */
@Entity(tableName = "pdf_bookmarks", primaryKeys = ["entryId", "pageIndex"])
data class PdfBookmark(
    val entryId: Long,
    val pageIndex: Int,
    val label: String,
    val createdAt: Long,
)

@Dao
interface PdfBookmarkDao {

    @Query("SELECT * FROM pdf_bookmarks WHERE entryId = :entryId ORDER BY pageIndex")
    suspend fun forEntry(entryId: Long): List<PdfBookmark>

    @Upsert
    suspend fun save(bookmark: PdfBookmark)

    @Query("DELETE FROM pdf_bookmarks WHERE entryId = :entryId AND pageIndex = :page")
    suspend fun delete(entryId: Long, page: Int)

    @Query("DELETE FROM pdf_bookmarks WHERE entryId = :entryId")
    suspend fun clear(entryId: Long)

    @Query("DELETE FROM pdf_bookmarks WHERE entryId IN (:ids)")
    suspend fun forgetEntries(ids: List<Long>)
}
