package com.qoder.pocketvault.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * 阅读统计的一格：某本书在某一天累计读了多久、推进了多少"块"。
 *
 * [day] 用本地日期字符串（`2026-10-12`）而不是时间戳，是为了"按天聚合"这件事交给 SQL 的
 * GROUP BY 就够了，不用在 Kotlin 里按时区来回换算；跨零点自然落到下一天。
 * [blocks] 对小说是段落数、对 PDF 是页数、对视频用不上就填 0——一列三个用途，
 * 反正界面只会写成"推进了 N 处"。
 */
@Entity(tableName = "reading_stats", primaryKeys = ["entryId", "day"])
data class ReadingStat(
    val entryId: Long,
    val day: String,
    val seconds: Int,
    val blocks: Int,
)

/** 一本书的累计统计（排行用）。 */
data class BookStat(
    val entryId: Long,
    val seconds: Int,
    val blocks: Int,
)

/** 一天的累计统计（连续天数、热图用）。 */
data class DayStat(
    val day: String,
    val seconds: Int,
)

@Dao
interface ReadingStatDao {

    @Query("SELECT * FROM reading_stats WHERE entryId = :entryId AND day = :day")
    suspend fun byEntryDay(entryId: Long, day: String): ReadingStat?

    @Upsert
    suspend fun save(stat: ReadingStat)

    @Query("SELECT entryId AS entryId, SUM(seconds) AS seconds, SUM(blocks) AS blocks FROM reading_stats GROUP BY entryId ORDER BY seconds DESC")
    suspend fun perBook(): List<BookStat>

    @Query("SELECT day AS day, SUM(seconds) AS seconds FROM reading_stats GROUP BY day ORDER BY day DESC LIMIT :limit")
    suspend fun recentDays(limit: Int): List<DayStat>

    @Query("SELECT IFNULL(SUM(seconds), 0) FROM reading_stats")
    suspend fun totalSeconds(): Int

    @Query("SELECT COUNT(DISTINCT day) FROM reading_stats")
    suspend fun activeDays(): Int

    @Query("DELETE FROM reading_stats WHERE entryId = :entryId")
    suspend fun clear(entryId: Long)

    @Query("DELETE FROM reading_stats WHERE entryId IN (:ids)")
    suspend fun forgetEntries(ids: List<Long>)
}
