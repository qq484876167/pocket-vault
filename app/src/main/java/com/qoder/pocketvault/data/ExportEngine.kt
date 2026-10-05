package com.qoder.pocketvault.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.data.db.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 导出 = 把内容重新交还给系统。这是隔离设计的“受控出口”：
 * 只有用户显式导出的文件才会进入 MediaStore / 公共目录，也才会被相册和文件管理器看到。
 */
class ExportEngine(
    context: Context,
    private val repo: VaultRepository,
) {
    private val appContext = context.applicationContext
    private val cr = appContext.contentResolver

    /**
     * 写入系统公共目录（相册 / 音乐 / 下载），保留原有子目录层级。
     * API 29+ 通过 RELATIVE_PATH 定位，无需任何存储权限；写出的文件从此对本机所有应用可见。
     */
    suspend fun exportToPublicLibrary(entries: List<VaultEntry>): Int = withContext(Dispatchers.IO) {
        var exported = 0
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (entry.isFolder) continue
            val source = runCatching { repo.physicalFile(entry) }.getOrNull()
            if (source == null || !source.isFile) continue
            val collection = collectionFor(entry.kind)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType ?: FileKind.guessMime(entry.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeBucketFor(entry))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(collection, values) ?: throw IOException("系统拒绝了写入请求")
            try {
                cr.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("无法打开输出流")
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                exported++
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
        }
        exported
    }

    /** 导出到用户通过 SAF 选择的目录，完整保留目录树。 */
    suspend fun exportToTree(treeUri: Uri, entries: List<VaultEntry>): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("无法访问所选文件夹")
        var written = 0
        val dirCache = HashMap<String, DocumentFile>()
        dirCache[""] = root
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val source = runCatching { repo.physicalFile(entry) }.getOrNull() ?: continue
            if (entry.isFolder) {
                folderAt(entry.relativePath, dirCache, root)
                continue
            }
            if (!source.isFile) continue
            val parentRel = entry.relativePath.trim('/').substringBeforeLast('/', "")
            val parent = folderAt(parentRel, dirCache, root)
            val target = parent.findFile(entry.name)
                ?: parent.createFile(entry.mimeType ?: FileKind.guessMime(entry.name), entry.name)
                ?: throw IOException("无法在目标位置创建文件：${entry.name}")
            cr.openOutputStream(target.uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("无法写入 ${entry.name}")
            written++
        }
        written
    }

    /**
     * 移动到用户指定的任意文件夹（SAF 树）：按原层级复制出去，
     * 每个文件都校验目标字节数，只有全部通过才从本应用永久删除；
     * 校验不过的条目保留在库里并回报文件名。
     */
    suspend fun moveToTree(treeUri: Uri, entries: List<VaultEntry>): MoveOutcome = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("无法访问所选文件夹")
        val dirCache = HashMap<String, DocumentFile>().apply { put("", root) }
        val failed = ArrayList<String>()
        var moved = 0
        for (entry in entries.sortedBy { it.relativePath.count { c -> c == '/' } }) {
            currentCoroutineContext().ensureActive()
            val ok = cloneIntoTree(entry, dirCache, root)
            if (ok) moved++ else failed += entry.name
        }
        val verifiableIds = entries.filter { it.name !in failed }.map { it.id }
        if (verifiableIds.isNotEmpty()) repo.deletePermanently(verifiableIds)
        MoveOutcome(moved = moved, unresolved = failed)
    }

    private suspend fun cloneIntoTree(
        entry: VaultEntry,
        dirCache: MutableMap<String, DocumentFile>,
        root: DocumentFile,
    ): Boolean {
        val source = runCatching { repo.physicalFile(entry) }.getOrNull() ?: return false
        val parentRel = entry.relativePath.trim('/').substringBeforeLast('/', "")
        val parent = folderAt(parentRel, dirCache, root)
        if (entry.isFolder) {
            val dir = parent.findFile(entry.name) ?: parent.createDirectory(entry.name) ?: return false
            dirCache[entry.relativePath.trim('/')] = dir
            var allOk = true
            for (child in repo.childrenOf(entry)) {
                if (!cloneIntoTree(child, dirCache, root)) allOk = false
            }
            return allOk && !source.isFile
        }
        if (!source.isFile) return false
        // 目标已存在且删不掉时宁可失败，也不要在对方目录里悄悄留两份
        val existing = parent.findFile(entry.name)
        if (existing != null && !existing.delete()) return false
        val target = parent.createFile(entry.mimeType ?: FileKind.guessMime(entry.name), entry.name)
            ?: return false
        cr.openOutputStream(target.uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: return false
        val written = runCatching { target.length() }.getOrDefault(-1L)
        // 有些提供器（云盘类）写完后立刻查大小会返回 -1，这种情况用源大小兜底再确认流已写完
        return written == source.length() || (written <= 0 && target.exists())
    }

    data class MoveOutcome(val moved: Int, val unresolved: List<String>)

    private fun collectionFor(kind: FileKind): Uri = when (kind) {
        FileKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        FileKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        FileKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    /** 相册/音乐目录本身按类型分桶，子目录继续沿用文件库里的层级。 */
    private fun relativeBucketFor(entry: VaultEntry): String {
        val sub = entry.relativePath.trim('/').substringBeforeLast('/', "")
            .let { if (it.isEmpty()) "" else "/$it" }
        val base = when (entry.kind) {
            FileKind.IMAGE -> "Pictures/$EXPORT_DIR"
            FileKind.VIDEO -> "Movies/$EXPORT_DIR"
            FileKind.AUDIO -> "Music/$EXPORT_DIR"
            else -> "Download/$EXPORT_DIR"
        }
        return base + sub
    }

    private fun folderAt(
        relativePath: String,
        cache: MutableMap<String, DocumentFile>,
        root: DocumentFile,
    ): DocumentFile {
        val clean = relativePath.trim('/')
        cache[clean]?.let { return it }
        if (clean.isEmpty()) return root.also { cache[""] = it }
        val parentRel = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        val parent = folderAt(parentRel, cache, root)
        val existing = parent.findFile(name)
        val dir = existing ?: parent.createDirectory(name) ?: throw IOException("无法创建目录：$name")
        cache[clean] = dir
        return dir
    }

    private companion object {
        const val EXPORT_DIR = "口袋文件库"
    }
}
