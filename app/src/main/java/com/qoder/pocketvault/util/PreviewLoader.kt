package com.qoder.pocketvault.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.data.VaultRepository
import com.qoder.pocketvault.data.db.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface PreviewContent {
    data object None : PreviewContent
    data class Image(val file: File) : PreviewContent
    data class Video(val thumb: File?) : PreviewContent
    data class Text(val text: String, val truncated: Boolean) : PreviewContent
    data class Pdf(val pages: List<Bitmap>, val totalPages: Int, val truncated: Boolean) : PreviewContent
}

/**
 * 应用内预览：图片 / 视频首帧 / 纯文本 / PDF。
 * 音频与 Office 文档交给外部应用（见 [FileBridge]），避免为了预览引入解码库。
 */
class PreviewLoader(
    private val repo: VaultRepository,
    private val thumbs: com.qoder.pocketvault.data.ThumbnailStore,
) {

    suspend fun load(entry: VaultEntry): PreviewContent = withContext(Dispatchers.IO) {
        val file = runCatching { repo.physicalFile(entry) }.getOrNull()
        if (file == null) return@withContext PreviewContent.None
        when (entry.kind) {
            FileKind.IMAGE -> if (file.isFile) PreviewContent.Image(file) else PreviewContent.None
            FileKind.VIDEO -> PreviewContent.Video(thumbs.thumbFor(entry))
            FileKind.DOCUMENT -> when (FileKind.extensionOf(entry.name)) {
                "pdf" -> pdf(file)
                in TEXT_EXT -> text(file)
                else -> PreviewContent.None
            }

            else -> PreviewContent.None
        }
    }

    private fun text(file: File): PreviewContent {
        if (!file.isFile) return PreviewContent.None
        val head = ByteArray(TEXT_PREVIEW_BYTES.toInt())
        val read = runCatching {
            file.inputStream().use { it.read(head) }
        }.getOrDefault(0)
        if (read <= 0) return PreviewContent.Text("", false)
        val bytes = head.copyOfRange(0, read)
        val decoded = decodeSmart(bytes)
        val stripped = decoded.removePrefix("\uFEFF")
        return PreviewContent.Text(
            text = if (stripped.isBlank() && decoded.isNotEmpty()) "（可能是二进制或使用了未知编码，无法显示）" else stripped,
            truncated = file.length() > read,
        )
    }

    /** 中文 TXT 常见 UTF-8 / GB18030 两种编码，先严格试 UTF-8，失败再退回 GB18030。 */
    private fun decodeSmart(bytes: ByteArray): String {
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            runCatching { String(bytes, Charset.forName("GB18030")) }.getOrElse { String(bytes, StandardCharsets.ISO_8859_1) }
        }
    }

    private fun pdf(file: File): PreviewContent {
        if (!file.isFile) return PreviewContent.None
        val descriptor = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
            .getOrNull() ?: return PreviewContent.None
        return try {
            PdfRenderer(descriptor).use { renderer ->
                val count = renderer.pageCount
                val limit = minOf(count, PDF_MAX_PAGES)
                val pages = ArrayList<Bitmap>(limit)
                for (index in 0 until limit) {
                    renderer.openPage(index).use { page ->
                        val scale = (PDF_WIDTH.toFloat() / page.width.coerceAtLeast(1)).coerceAtMost(1f)
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages += bitmap
                    }
                }
                PreviewContent.Pdf(pages, count, count > limit)
            }
        } catch (e: Exception) {
            PreviewContent.None
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private companion object {
        // 详情页只给一段导语：整本小说塞进一个 Text 会让界面卡死，全文阅读走应用内阅读器
        const val TEXT_PREVIEW_BYTES = 4L * 1024
        const val PDF_MAX_PAGES = 12
        const val PDF_WIDTH = 900
        val TEXT_EXT = setOf("txt", "md", "markdown", "log", "csv", "json", "xml", "yaml", "yml", "ini", "conf", "sql")
    }
}
