package com.qoder.pocketvault.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.data.db.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 缩略图缓存放在 cacheDir，属于可随时丢弃的派生数据：
 * 系统或用户清理缓存都不会损伤文件库本体。
 */
class ThumbnailStore(context: Context, private val repo: VaultRepository) {

    private val dir: File = File(context.cacheDir, "thumbs").apply { mkdirs() }

    /** 返回可直接交给 Coil 的缓存文件；null 表示界面应改用类型图标。 */
    suspend fun thumbFor(entry: VaultEntry): File? = withContext(Dispatchers.IO) {
        when (entry.kind) {
            FileKind.VIDEO -> videoFrame(entry)
            else -> null // 图片由 Coil 直接按需降采样读取私有目录里的原文件
        }
    }

    private fun videoFrame(entry: VaultEntry): File? {
        val cacheFile = File(dir, "v_${entry.id}_${entry.modifiedAtMillis}.jpg")
        if (cacheFile.isFile && cacheFile.length() > 0) return cacheFile

        val physical = runCatching { repo.physicalFile(entry) }.getOrNull() ?: return null
        if (!physical.isFile) return null

        val retriever = MediaMetadataRetriever()
        val frame: Bitmap? = try {
            retriever.setDataSource(physical.absolutePath)
            retriever.getFrameAtTime(0)
        } catch (e: Exception) {
            Log.w(TAG, "取帧失败: ${entry.name}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
        if (frame == null) return null

        val tmp = File(dir, cacheFile.name + ".part")
        var sized: Bitmap? = null
        return try {
            val width = frame.width.coerceAtLeast(1)
            val scale = TARGET_WIDTH.toFloat() / width
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frame,
                    TARGET_WIDTH,
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                frame
            }
            sized = scaled
            val ok = FileOutputStream(tmp).use { out ->
                val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
                out.flush()
                compressed
            }
            when {
                !ok -> null
                !tmp.renameTo(cacheFile) -> null
                else -> cacheFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "写缩略图失败: ${entry.name}", e)
            null
        } finally {
            // 中途失败留下的 .part 以前没人清：设置页统计到的缓存占用就是这些残留
            runCatching { if (tmp.exists()) tmp.delete() }
            runCatching { frame.recycle() }
            if (sized != null && sized !== frame) runCatching { sized.recycle() }
        }
    }

    /** 缓存目录路径，设置页用于显示占用空间与手动清理。 */
    fun cacheDirPath(): String = dir.absolutePath

    /** 只保留最近若干帧，避免缓存无限增长。 */
    fun trimCache(maxFiles: Int = 600) {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxFiles)
            .forEach { runCatching { it.delete() } }
    }

    fun clearCache() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val TARGET_WIDTH = 480
        const val TAG = "ThumbnailStore"
    }
}
