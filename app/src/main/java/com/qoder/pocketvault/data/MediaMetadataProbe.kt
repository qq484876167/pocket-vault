package com.qoder.pocketvault.data

import android.media.MediaMetadataRetriever
import android.util.Log
import com.qoder.pocketvault.core.FileKind
import java.io.File

data class MediaMeta(val width: Int?, val height: Int?, val durationMs: Long?)

/**
 * 从私有目录里的文件读取尺寸/时长。只读元数据，不写 MediaStore，
 * 因此不会让内容出现在系统相册或系统音乐库里。
 */
object MediaMetadataProbe {

    private const val TAG = "MediaMetadataProbe"

    fun probe(file: File, kind: FileKind): MediaMeta = when (kind) {
        FileKind.IMAGE -> probeImage(file)
        FileKind.VIDEO, FileKind.AUDIO -> probeWithRetriever(file)
        else -> MediaMeta(null, null, null)
    }

    private fun probeImage(file: File): MediaMeta {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath, options) }
        val w = options.outWidth.takeIf { it > 0 }
        val h = options.outHeight.takeIf { it > 0 }
        return MediaMeta(w, h, null)
    }

    private fun probeWithRetriever(file: File): MediaMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.takeIf { it > 0 }
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.takeIf { it > 0 }
            // 旋转 90/270 的设备元数据宽高是反的，按视觉方向修正后再入库。
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val oriented = if (rotation == 90 || rotation == 270) {
                MediaMeta(height, width, duration)
            } else {
                MediaMeta(width, height, duration)
            }
            oriented
        } catch (e: Exception) {
            Log.w(TAG, "读取媒体元数据失败: ${file.name}", e)
            MediaMeta(null, null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
