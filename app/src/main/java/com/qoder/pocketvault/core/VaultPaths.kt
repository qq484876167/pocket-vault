package com.qoder.pocketvault.core

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException

enum class VaultRootMode { INTERNAL, EXTERNAL_SANDBOX }

/**
 * 私有存储的唯一入口。数据库只保存相对路径，物理位置全部由此类推导，
 * 因此上层拿不到越过根目录的 File。
 */
class VaultPaths(
    private val context: Context,
    private val prefs: VaultPrefs,
) {
    @Volatile
    private var rootDir: File = resolveRootDir(context, prefs.rootMode)

    val root: File get() = rootDir
    val trashDir: File get() = File(rootDir, TRASH_DIR)

    fun mode(): VaultRootMode = prefs.rootMode

    fun rootPath(): String = rootDir.canonicalPathSafe()

    fun pathFor(mode: VaultRootMode): String = resolveRootDir(context, mode).canonicalPathSafe()

    fun ensureRoot() {
        if (!rootDir.exists()) rootDir.mkdirs()
        File(rootDir, TRASH_DIR).mkdirs()
        // 双保险：根目录切到外存沙箱时阻止 MediaStore 扫描（内部存储本来就不会被扫描）。
        val marker = File(rootDir, ".nomedia")
        if (!marker.exists()) runCatching { marker.createNewFile() }
    }

    /** 切换根目录只在库为空时允许，避免出现半迁移状态。 */
    fun switchRoot(mode: VaultRootMode, isEmpty: Boolean): Boolean {
        if (mode == prefs.rootMode) return true
        if (!isEmpty) return false
        prefs.rootMode = mode
        rootDir = resolveRootDir(context, mode)
        ensureRoot()
        return true
    }

    fun usableBytes(): Long = try {
        StatFs(rootDir.absolutePath).availableBytes
    } catch (_: Exception) {
        0L
    }

    /** 相对路径 -> File，带符号链接级的越界校验。 */
    fun fileOf(relativePath: String): File {
        val clean = normalizeRelative(relativePath)
        val candidate = if (clean.isEmpty()) rootDir else File(rootDir, clean)
        return requireInsideRoot(candidate, relativePath)
    }

    private fun requireInsideRoot(candidate: File, original: String): File {
        val rootCanonical = try {
            rootDir.canonicalPath
        } catch (e: IOException) {
            throw IllegalStateException("无法解析私有根目录", e)
        }
        val canonical = try {
            candidate.canonicalPath
        } catch (e: IOException) {
            throw IllegalArgumentException("非法路径: $original", e)
        }
        if (canonical != rootCanonical && !canonical.startsWith(rootCanonical + File.separator)) {
            throw IllegalArgumentException("越出私有目录: $original")
        }
        return candidate
    }

    /** File -> 相对路径（'/' 分隔）；不在根内时抛异常。用于对账与调试。 */
    fun relativeOf(file: File): String {
        val rootCanonical = rootDir.canonicalPath
        val canonical = file.canonicalPath
        if (canonical == rootCanonical) return ""
        require(canonical.startsWith(rootCanonical + File.separator)) { "文件不在私有目录内" }
        return canonical.removePrefix(rootCanonical + File.separator).replace(File.separatorChar, '/')
    }

    companion object {
        const val TRASH_DIR = ".trash"
        const val MAX_NAME_LENGTH = 180
        private val RESERVED = setOf(TRASH_DIR, ".nomedia")

        /**
         * 校验并规范化相对路径：拒绝 ..、保留名与控制字符。
         * 返回 '' 表示根目录。
         */
        fun normalizeRelative(relativePath: String): String {
            if (relativePath.isBlank()) return ""
            val segments = relativePath.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
            val out = StringBuilder()
            for (segment in segments) {
                require(segment != "..") { "越界路径段: $relativePath" }
                require(segment !in RESERVED) { "保留目录/文件不可访问: $segment" }
                require(segment.none { it < ' ' }) { "路径包含控制字符" }
                if (out.isNotEmpty()) out.append('/')
                out.append(segment)
            }
            return out.toString()
        }

        /** 落盘安全名：替换分隔符、去掉首尾点与空格，保留长度信息用于查重。 */
        fun sanitizeName(rawName: String): String {
            val replaced = rawName.map { c ->
                when {
                    c == '/' || c == '\\' -> '_'
                    c == '\u0000' -> '_'
                    c < ' ' -> '_'
                    else -> c
                }
            }.joinToString("")
            var name = replaced.trim().trimEnd('.').trim()
            if (name.isEmpty() || name == "." || name == "..") name = "未命名文件"
            if (name.startsWith(".")) name = "_" + name // 避免创建隐藏文件或撞上保留名
            if (name in RESERVED) name = "_$name"
            if (name.length > MAX_NAME_LENGTH) {
                val ext = name.substringAfterLast('.', "")
                val stem = name.substringBeforeLast('.', name)
                name = if (ext.isNotEmpty() && ext.length < 12) {
                    stem.take(MAX_NAME_LENGTH - ext.length - 1) + "." + ext
                } else {
                    stem.take(MAX_NAME_LENGTH)
                }
            }
            return name
        }

        internal fun File.canonicalPathSafe(): String =
            runCatching { canonicalPath }.getOrElse { absolutePath }

        private fun resolveRootDir(context: Context, mode: VaultRootMode): File {
            val base = when (mode) {
                // /data/user/0/<pkg>/files/vault —— SELinux 沙箱内，MTP 与文件管理器均不可见
                VaultRootMode.INTERNAL -> context.filesDir
                // /sdcard/Android/data/<pkg>/files/vault —— Android 11+ 对其他应用不可读，容量更大
                VaultRootMode.EXTERNAL_SANDBOX ->
                    context.getExternalFilesDir(null) ?: context.filesDir
            }
            return File(base, "vault")
        }
    }
}
