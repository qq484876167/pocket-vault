package com.qoder.pocketvault.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.qoder.pocketvault.core.FileKind

/** -1 表示私有根目录，避免 NULL 在查询与索引里的额外语义负担。 */
const val ROOT_ID = -1L

/** 回收站虚拟父节点：进入回收站的条目把 parentId 指向这里，列表查询天然过滤掉它们。 */
const val TRASH_PARENT_ID = -2L

/** 回收站条目的逻辑路径前缀，保证与真实磁盘路径不冲突且不占用导入路径。 */
const val TRASH_PATH_PREFIX = "@trash"

enum class EntryState { ACTIVE, TRASHED }

@Entity(
    tableName = "entries",
    indices = [
        Index(value = ["relativePath"], unique = true),
        Index("parentId"),
        Index("kind"),
        Index("state"),
        Index("sourceSignature"),
        Index("importedAtMillis"),
    ],
)
data class VaultEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 相对私有根目录的逻辑路径，'/' 分隔，如 照片/2026/IMG_0001.jpg */
    val relativePath: String,
    val name: String,
    val parentId: Long,
    val kind: FileKind,
    val mimeType: String?,
    val sizeBytes: Long,
    /** 源文件的修改时间；取不到时回落为导入时间。 */
    val modifiedAtMillis: Long,
    val importedAtMillis: Long,
    /** 用于增量导入去重：树 uri + documentId + size + mtime。 */
    val sourceSignature: String?,
    val state: EntryState = EntryState.ACTIVE,
    val trashedAtMillis: Long? = null,
    /** 直接删除的条目为 null；随父目录一起进回收站的后代记录其删除根节点 id。 */
    val trashedVia: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val favorite: Boolean = false,
    /** 回收站里记录原本所在目录，恢复时优先回到原位置。 */
    val trashOriginParentId: Long? = null,
) {
    val isFolder: Boolean get() = kind == FileKind.FOLDER
    val resolution: String?
        get() = if (width != null && height != null && width > 0) "${width}×${height}" else null
}

data class KindStat(val kind: FileKind, val itemCount: Int, val totalBytes: Long)

class Converters {
    @TypeConverter
    fun kindToString(value: FileKind): String = value.name

    @TypeConverter
    fun stringToKind(value: String): FileKind =
        runCatching { FileKind.valueOf(value) }.getOrDefault(FileKind.OTHER)

    @TypeConverter
    fun stateToString(value: EntryState): String = value.name

    @TypeConverter
    fun stringToState(value: String): EntryState =
        runCatching { EntryState.valueOf(value) }.getOrDefault(EntryState.ACTIVE)
}

/** 与 UI 交换的排序选项，SQL 只保证粗排，中文按拼音排序在 Kotlin 侧完成。 */
enum class SortField { NAME, SIZE, MODIFIED, IMPORTED }

data class ListOptions(val field: SortField = SortField.NAME, val ascending: Boolean = true)
