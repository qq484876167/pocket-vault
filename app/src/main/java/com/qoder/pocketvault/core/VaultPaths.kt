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
        const val TRASH_DIR = PathRules.TRASH_DIR
        const val MAX_NAME_LENGTH = PathRules.MAX_NAME_LENGTH

    /** 规则本体在 [PathRules]（不依赖 Context，可脱离设备测试），这里只转发。 */
        fun normalizeRelative(relativePath: String): String = PathRules.normalizeRelative(relativePath)

        fun sanitizeName(rawName: String): String = PathRules.sanitizeName(rawName)

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
