package com.qoder.pocketvault.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.TypeConverters
import androidx.room.Update
import com.qoder.pocketvault.core.FileKind
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    @Insert
    suspend fun insert(entry: VaultEntry): Long

    @Insert
    suspend fun insertAll(entries: List<VaultEntry>): List<Long>

    @Update
    suspend fun update(entry: VaultEntry)

    @Update
    suspend fun updateAll(entries: List<VaultEntry>)

    @Delete
    suspend fun delete(entries: List<VaultEntry>)

    @Query("DELETE FROM entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM entries WHERE parentId = :parentId AND state = 'ACTIVE'")
    fun observeChildren(parentId: Long): Flow<List<VaultEntry>>

    @Query("SELECT * FROM entries WHERE parentId = :parentId AND state = 'ACTIVE'")
    suspend fun children(parentId: Long): List<VaultEntry>

    @Query("SELECT * FROM entries WHERE parentId = :parentId")
    suspend fun childrenAnyState(parentId: Long): List<VaultEntry>

    @Query("SELECT * FROM entries WHERE relativePath = :path LIMIT 1")
    suspend fun byPath(path: String): VaultEntry?

    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): VaultEntry?

    @Query("SELECT * FROM entries WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<VaultEntry>

    @Query(
        """
        SELECT * FROM entries
        WHERE state = 'ACTIVE' AND kind <> 'FOLDER'
        ORDER BY importedAtMillis DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<VaultEntry>>

    @Query(
        """
        SELECT * FROM entries
        WHERE state = 'ACTIVE' AND kind = :kind
        ORDER BY modifiedAtMillis DESC
        LIMIT :limit
        """
    )
    fun observeByKind(kind: FileKind, limit: Int): Flow<List<VaultEntry>>

    @Query("SELECT * FROM entries WHERE state = 'ACTIVE' AND favorite = 1 ORDER BY modifiedAtMillis DESC")
    fun observeFavorites(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM entries WHERE state = 'TRASHED' ORDER BY trashedAtMillis DESC")
    fun observeTrash(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM entries WHERE state = 'TRASHED' ORDER BY trashedAtMillis DESC")
    suspend fun trashEntries(): List<VaultEntry>

    @Query("SELECT * FROM entries WHERE state = 'TRASHED' AND trashedAtMillis IS NOT NULL AND trashedAtMillis < :cutoff")
    suspend fun expiredTrash(cutoff: Long): List<VaultEntry>

    /**
     * :likePattern 必须已经用 ESCAPE '\' 转义过 % 和 _，由调用方负责。
     * :scopePath 为 null 表示不限范围；传 `某目录/` 时因为 relativePath 是完整路径，
     * 一次前缀比较就覆盖该目录的整棵子树，也不会串到 `某目录2/` 这类兄弟目录上。
     *
     * 范围用「前缀等值」而不是 LIKE：SQLite 的 LIKE 对 ASCII 恒为大小写不敏感
     * （只有 case_sensitive_like 能改，加 COLLATE 压不住，实测过），`photos/` 会误命中 `Photos/`；
     * 等值比较走 BINARY，天然区分大小写，也不必再给目录名做通配符转义。
     */
    @Query(
        """
        SELECT * FROM entries
        WHERE state = 'ACTIVE' AND kind <> 'FOLDER'
          AND name LIKE '%' || :likePattern || '%' ESCAPE '\'
          AND (:kind IS NULL OR kind = :kind)
          AND (:scopePath IS NULL OR substr(relativePath, 1, length(:scopePath)) = :scopePath)
        ORDER BY modifiedAtMillis DESC
        LIMIT :limit
        """
    )
    suspend fun search(likePattern: String, kind: FileKind?, scopePath: String?, limit: Int): List<VaultEntry>

    @Query(
        """
        SELECT kind AS kind, COUNT(*) AS itemCount, IFNULL(SUM(sizeBytes), 0) AS totalBytes
        FROM entries
        WHERE state = 'ACTIVE' AND kind <> 'FOLDER'
        GROUP BY kind
        """
    )
    fun observeStats(): Flow<List<KindStat>>

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun rowCount(): Int

    @Query("SELECT IFNULL(SUM(sizeBytes), 0) FROM entries WHERE state = 'ACTIVE'")
    suspend fun usedBytes(): Long

    @Query("SELECT COUNT(*) FROM entries WHERE state = 'ACTIVE' AND kind <> 'FOLDER'")
    suspend fun activeFileCount(): Int

    @Query("SELECT * FROM entries WHERE sourceSignature = :signature LIMIT 1")
    suspend fun bySignature(signature: String): VaultEntry?

    @Query("SELECT relativePath FROM entries WHERE state = 'ACTIVE' AND kind = 'FOLDER' ORDER BY relativePath")
    suspend fun folderPaths(): List<String>

    /** 查看器序列：同目录下同类型的所有条目（名称排序在 Kotlin 侧按拼音完成）。 */
    @Query("SELECT * FROM entries WHERE parentId = :parentId AND state = 'ACTIVE' AND kind = :kind")
    suspend fun siblingsOfKind(parentId: Long, kind: FileKind): List<VaultEntry>

    @Query("SELECT * FROM entries WHERE state = 'ACTIVE' AND kind = 'FOLDER'")
    suspend fun allFolders(): List<VaultEntry>

    @Query("SELECT COUNT(*) FROM entries WHERE state = 'ACTIVE' AND kind = 'FOLDER'")
    suspend fun folderCount(): Int

    @Query("UPDATE entries SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)
}
