package com.qoder.pocketvault.core

import android.content.Context
import androidx.core.content.edit

/**
 * 偏好设置。注意：备份规则里显式排除了 vault_prefs.xml，
 * 否则“选择了哪个根目录”这类信息会被备份带走。
 */
class VaultPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit { putBoolean(KEY_ONBOARDED, value) }

    var rootMode: VaultRootMode
        get() = runCatching { VaultRootMode.valueOf(sp.getString(KEY_ROOT_MODE, null) ?: VaultRootMode.INTERNAL.name) }
            .getOrDefault(VaultRootMode.INTERNAL)
        set(value) = sp.edit { putString(KEY_ROOT_MODE, value.name) }

    var trashRetentionDays: Int
        get() = sp.getInt(KEY_TRASH_DAYS, 30).coerceIn(1, 365)
        set(value) = sp.edit { putInt(KEY_TRASH_DAYS, value.coerceIn(1, 365)) }

    /** 阅读排版偏好：字号、行距倍数、配色主题序号。 */
    var readingFontSize: Float
        get() = sp.getFloat(KEY_READING_FONT, 18f).coerceIn(13f, 32f)
        set(value) = sp.edit { putFloat(KEY_READING_FONT, value.coerceIn(13f, 32f)) }

    var readingLineSpacing: Float
        get() = sp.getFloat(KEY_READING_LINE, 1.75f).coerceIn(1.3f, 2.4f)
        set(value) = sp.edit { putFloat(KEY_READING_LINE, value.coerceIn(1.3f, 2.4f)) }

    var readingTheme: Int
        get() = sp.getInt(KEY_READING_THEME, 1).coerceIn(0, 3)
        set(value) = sp.edit { putInt(KEY_READING_THEME, value.coerceIn(0, 3)) }

    var readingMargin: Int
        get() = sp.getInt(KEY_READING_MARGIN, 18).coerceIn(8, 40)
        set(value) = sp.edit { putInt(KEY_READING_MARGIN, value.coerceIn(8, 40)) }

    /** 只读持久化树授权，用于“再次同步同一文件夹”的增量导入。 */
    var watchedTreeUri: String?
        get() = sp.getString(KEY_WATCHED_TREE, null)
        set(value) = sp.edit {
            if (value == null) remove(KEY_WATCHED_TREE) else putString(KEY_WATCHED_TREE, value)
        }

    var watchedTreeLabel: String?
        get() = sp.getString(KEY_WATCHED_LABEL, null)
        set(value) = sp.edit {
            if (value == null) remove(KEY_WATCHED_LABEL) else putString(KEY_WATCHED_LABEL, value)
        }

    /**
     * 阅读位置的最后一道保险。Room 的写入排在协程队列里，进程被划掉时可能没轮到就死了；
     * 而 SharedPreferences 的 apply() 有系统在退出前强制落盘的待遇。
     * 打开书时拿它和库里的记录比时间戳，取更新的那个。
     */
    var lastReading: LastReading?
        get() = sp.getString(KEY_LAST_READING, null)?.let { LastReading.parse(it) }
        set(value) = sp.edit {
            if (value == null) remove(KEY_LAST_READING) else putString(KEY_LAST_READING, value.serialize())
        }

    /**
     * 最近用过的目标目录（移动 / 复制 / 解压的目标位置），新在前，最多 5 条。
     * 根目录本身是空串，存储里用 "/" 当哨兵；目录名里不会有换行（`sanitizeName` 把控制字符
     * 换成 `_`），所以用换行做分隔符不会歧义。
     */
    var folderTargets: List<String>
        get() = sp.getString(KEY_FOLDER_TARGETS, null)
            ?.split('\n')
            ?.filter { it.isNotEmpty() }
            ?.map { if (it == "/") "" else it }
            ?: emptyList()
        set(value) = sp.edit {
            val kept = value.distinct().take(MAX_FOLDER_TARGETS)
            if (kept.isEmpty()) remove(KEY_FOLDER_TARGETS)
            else putString(KEY_FOLDER_TARGETS, kept.joinToString("\n") { if (it.isEmpty()) "/" else it })
        }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_ROOT_MODE = "root_mode"
        const val KEY_TRASH_DAYS = "trash_days"
        const val KEY_WATCHED_TREE = "watched_tree"
        const val KEY_WATCHED_LABEL = "watched_label"
        const val KEY_FOLDER_TARGETS = "folder_targets"
        const val MAX_FOLDER_TARGETS = 5
        const val KEY_READING_FONT = "reading_font"
        const val KEY_READING_LINE = "reading_line"
        const val KEY_READING_THEME = "reading_theme"
        const val KEY_READING_MARGIN = "reading_margin"
        const val KEY_LAST_READING = "last_reading"
    }
}

/** prefs 里镜像的最后一次阅读位置；只在 entryId 对得上时才有意义。 */
data class LastReading(
    val entryId: Long,
    val chapter: Int,
    val paragraph: Int,
    val at: Long,
) {
    fun serialize(): String = "$entryId|$chapter|$paragraph|$at"

    companion object {
        fun parse(text: String): LastReading? {
            val parts = text.split('|')
            if (parts.size != 4) return null
            val entry = parts[0].toLongOrNull() ?: return null
            val chapter = parts[1].toIntOrNull() ?: return null
            val paragraph = parts[2].toIntOrNull() ?: return null
            val at = parts[3].toLongOrNull() ?: return null
            return LastReading(entry, chapter, paragraph, at)
        }
    }
}
