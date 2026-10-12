package com.qoder.pocketvault.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * 媒体播放位置（视频 / 音频续播）。按 entryId 存，主键就是它。
 *
 * 只存"看到第几毫秒"和"这条媒体总共多长"两件事：
 *  - entryId 是稳定的，移动 / 重命名都不会让它变，所以换个目录回来照样能续上；
 *  - 存 durationMillis 是为了判断"基本看完了"——从头再来一遍比续在最后一帧更合理。
 *
 * 不外键到 entries：与既有的 reading_progress / reading_bookmarks 保持一致，
 * 删除路径统一由 [com.qoder.pocketvault.data.VaultRepository] 在彻底删除时显式清账，
 * 免得回收站里的东西被级联掉、还原后进度丢了。
 */
@Entity(tableName = "media_positions", primaryKeys = ["entryId"])
data class MediaPosition(
    val entryId: Long,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAt: Long,
)

@Dao
interface MediaPositionDao {

    @Query("SELECT * FROM media_positions WHERE entryId = :entryId")
    suspend fun byEntry(entryId: Long): MediaPosition?

    @Upsert
    suspend fun save(position: MediaPosition)

    @Query("DELETE FROM media_positions WHERE entryId = :entryId")
    suspend fun clear(entryId: Long)

    /** 彻底删除 / 清空回收站时连记账一起清掉，库里不能留下管不到的孤儿行。 */
    @Query("DELETE FROM media_positions WHERE entryId IN (:ids)")
    suspend fun forgetEntries(ids: List<Long>)
}
