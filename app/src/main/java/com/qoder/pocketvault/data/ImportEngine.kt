package com.qoder.pocketvault.data

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.core.VaultPrefs
import com.qoder.pocketvault.core.joinRelative
import com.qoder.pocketvault.data.db.EntryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

data class ImportProgress(
    val active: Boolean = false,
    val label: String = "",
    val totalItems: Int = 0,
    val doneItems: Int = 0,
    val totalBytes: Long = -1L,
    val doneBytes: Long = 0L,
    val skipped: Int = 0,
    val failed: Int = 0,
    val deletedSources: Int = 0,
    val deleteBlocked: Int = 0,
    val currentName: String = "",
    val message: String? = null,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0 && doneBytes > 0 -> (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            totalItems > 0 -> (doneItems.toFloat() / totalItems).coerceIn(0f, 1f)
            else -> 0f
        }

    val busy: Boolean get() = active
}

/** 源文件的一个候选条目（文件选择器返回的 uri，或树遍历出来的一个节点）。 */
private data class Source(
    val uri: Uri,
    val dirRelativePath: String,
    val hint: TreeHint?,
)

internal data class TreeHint(
    val name: String,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long,
    val signature: String?,
)

/**
 * 导入 = 复制到私有目录 + 写索引。源文件永不移动或删除，
 * 所以导入中断只会留下可清理的临时文件，不会破坏系统里的原始内容。
 */
class ImportEngine(
    context: Context,
    private val repo: VaultRepository,
    private val prefs: VaultPrefs,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val cr = appContext.contentResolver

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress

    private var job: Job? = null

    // ---------------------------------------------------------------- 对外入口

    /** 单个/多个文件导入。[deleteSource] 为 true 时，复制并入库成功后删除源文件（前提是该来源授予了写权限）。 */
    fun importFiles(uris: List<Uri>, destDirRelativePath: String, deleteSource: Boolean = false) {
        if (uris.isEmpty()) return
        val sources = uris.map { Source(it, VaultPaths.normalizeRelative(destDirRelativePath), null) }
        start(if (deleteSource) "移动 ${uris.size} 个文件" else "导入 ${uris.size} 个文件") {
            run(sources, deleteSource)
        }
    }

    /** 整个文件夹导入：以所选文件夹的名字建顶层目录，再保持原层级复制内容。 */
    fun importTree(
        treeUri: Uri,
        destDirRelativePath: String,
        rememberFolder: Boolean,
        deleteSource: Boolean = false,
    ) {
        start(if (deleteSource) "移动文件夹" else "导入文件夹") {
            if (rememberFolder) persistReadPermission(treeUri, withWrite = deleteSource)
            val nodes = listTree(treeUri)
            if (nodes.none { !it.isDir }) {
                finishWith("所选文件夹里没有文件")
                return@start
            }
            val base = VaultPaths.normalizeRelative(destDirRelativePath)
            val dest = joinRelative(base, VaultPaths.sanitizeName(treeLabel(treeUri)))
            val dirNodes = nodes.filter { it.isDir }
            for (node in dirNodes) {
                currentCoroutineContext().ensureActive()
                repo.ensureFolderPath(joinRelative(dest, node.relativeInside))
            }
            val sources = nodes.filter { !it.isDir }.map { node ->
                Source(node.uri, joinRelative(dest, node.relativeInside.substringBeforeLast('/', "")), node.hint)
            }
            run(sources, deleteSource)
            if (deleteSource) pruneEmptyDirs(treeUri, dirNodes)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _progress.value = _progress.value.copy(active = false, message = "已取消导入")
    }

    /** 关闭进度条，保留/清空摘要。 */
    fun dismissProgress() {
        _progress.value = ImportProgress()
    }

    /** 之前授权过的文件夹，用于“重新同步同一目录”的增量导入。 */
    fun persistedTrees(): List<UriPermission> =
        runCatching { cr.persistedUriPermissions.toList() }.getOrNull().orEmpty()
            .filter { DocumentsContract.isTreeUri(it.uri) && it.isReadPermission }

    fun forgetTree(uri: Uri) {
        runCatching {
            cr.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        if (prefs.watchedTreeUri == uri.toString()) {
            prefs.watchedTreeUri = null
            prefs.watchedTreeLabel = null
        }
    }

    // ---------------------------------------------------------------- 批量执行

    private suspend fun run(sources: List<Source>, deleteSource: Boolean = false) {
        val declared = sources.sumOf { it.hint?.size?.takeIf { size -> size > 0 } ?: 0L }
        val usable = repo.paths().usableBytes()
        if (declared > 0 && usable in 1..(declared + MIN_FREE_MARGIN)) {
            throw IOException("私有目录剩余空间不足（需要约 ${declared / 1024 / 1024} MB）")
        }
        _progress.value = _progress.value.copy(
            totalItems = sources.size,
            totalBytes = if (declared > 0) declared else -1L,
        )
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            _progress.value = _progress.value.copy(currentName = source.hint?.name ?: displayNameOf(source.uri))
            val base = _progress.value.doneBytes
            try {
                val outcome = importOne(source) { written ->
                    _progress.value = _progress.value.copy(doneBytes = base + written)
                }
                when (outcome) {
                    is Imported -> {
                        // 先确认字节已落地并入库，再删源；删除失败只影响"是否移动成功"，不影响副本安全
                        val deleted = if (deleteSource) deleteSourceFile(source.uri) else null
                        _progress.value = _progress.value.copy(
                            doneItems = _progress.value.doneItems + 1,
                            doneBytes = base + outcome.bytes,
                            deletedSources = _progress.value.deletedSources + if (deleted == true) 1 else 0,
                            deleteBlocked = _progress.value.deleteBlocked + if (deleted == false) 1 else 0,
                        )
                    }

                    Skipped -> _progress.value = _progress.value.copy(
                        skipped = _progress.value.skipped + 1,
                        doneBytes = base,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "单个文件导入失败: ${source.uri}", e)
                _progress.value = _progress.value.copy(
                    failed = _progress.value.failed + 1,
                    doneBytes = base,
                    message = _progress.value.message ?: e.userMessage(),
                )
            }
        }
    }

    /** 删除源文件；true=已删除，false=来源不允许删除。 */
    private fun deleteSourceFile(uri: Uri): Boolean {
        if (!DocumentsContract.isDocumentUri(appContext, uri)) return false
        return runCatching { DocumentsContract.deleteDocument(cr, uri) }.getOrDefault(false)
    }

    /**
     * 移动文件夹后清理被搬空的子目录：由深到浅，只删已经空掉的，
     * 且永不删除用户所选的那个根目录本身。
     */
    private suspend fun pruneEmptyDirs(treeUri: Uri, dirNodes: List<TreeNode>) {
        for (node in dirNodes.sortedByDescending { it.relativeInside.count { c -> c == '/' } }) {
            currentCoroutineContext().ensureActive()
            val docId = runCatching { DocumentsContract.getDocumentId(node.uri) }.getOrNull() ?: continue
            if (queryChildren(treeUri, docId).isNotEmpty()) continue
            deleteSourceFile(node.uri)
        }
    }

    private suspend fun importOne(source: Source, onBytes: (Long) -> Unit): ImportOutcome {
        val name = VaultPaths.sanitizeName(source.hint?.name ?: displayNameOf(source.uri))
        if (name.isBlank()) throw IOException("无法识别文件名")
        val signature = source.hint?.signature ?: fallbackSignature(source.uri, name)
        if (signature != null && repo.findBySignature(signature)?.state == EntryState.ACTIVE) return Skipped

        val allocation = repo.allocateFile(source.dirRelativePath, name)
        val written = try {
            streamToFile(source.uri, allocation.tempFile, onBytes)
        } catch (e: Exception) {
            repo.discardAllocation(allocation)
            throw e
        }
        if (written <= 0L) {
            repo.discardAllocation(allocation)
            return Skipped
        }
        repo.commitFile(
            allocation = allocation,
            sourceSignature = signature,
            sourceModifiedAt = source.hint?.lastModified?.takeIf { it > 0 },
            declaredSize = source.hint?.size?.takeIf { it > 0 },
            mimeType = source.hint?.mimeType ?: mimeOf(source.uri),
        )
        return Imported(written)
    }

    /** 流式落盘：不整块读入内存，同时提供可取消的进度上报。 */
    private suspend fun streamToFile(uri: Uri, temp: File, onBytes: (Long) -> Unit): Long {
        temp.parentFile?.mkdirs()
        var total = 0L
        var sinceReport = 0L
        val input = cr.openInputStream(uri) ?: throw FileNotFoundException("源文件不可读")
        input.use { source ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    sinceReport += read
                    if (sinceReport >= PROGRESS_STEP) {
                        onBytes(total)
                        sinceReport = 0L
                    }
                }
                output.fd.sync()
            }
        }
        onBytes(total)
        return total
    }

    private fun start(label: String, block: suspend CoroutineScope.() -> Unit) {
        if (job?.isActive == true) {
            _progress.value = _progress.value.copy(message = "已有导入任务在进行中，请等待或先取消")
            return
        }
        _progress.value = ImportProgress(active = true, label = label)
        job = scope.launch {
            try {
                block()
                val snapshot = _progress.value
                _progress.value = snapshot.copy(
                    active = false,
                    message = buildString {
                        append("完成，新增 ${snapshot.doneItems} 项")
                        if (snapshot.skipped > 0) append("，跳过 ${snapshot.skipped} 项（已存在，源文件保留）")
                        if (snapshot.deletedSources > 0) append("，已删除 ${snapshot.deletedSources} 个源文件")
                        if (snapshot.deleteBlocked > 0) append("，${snapshot.deleteBlocked} 个源文件无法删除（来源不允许）")
                        if (snapshot.failed > 0) append("，失败 ${snapshot.failed} 项")
                        snapshot.message?.let { append("（$it）") }
                    },
                )
            } catch (e: CancellationException) {
                _progress.value = _progress.value.copy(active = false, message = "已取消导入")
            } catch (e: Exception) {
                Log.w(TAG, "导入中断", e)
                _progress.value = _progress.value.copy(active = false, message = e.userMessage())
            }
        }
    }

    private fun finishWith(message: String) {
        _progress.value = _progress.value.copy(active = false, message = message)
    }

    // ---------------------------------------------------------------- 源遍历

    private data class TreeNode(
        val uri: Uri,
        val relativeInside: String,
        val isDir: Boolean,
        val hint: TreeHint,
    )

    private suspend fun listTree(treeUri: Uri): List<TreeNode> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = ArrayList<TreeNode>()
        val queue = ArrayDeque<Triple<String, String, Int>>()
        queue.addLast(Triple(rootDocId, "", 0))
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_DIRS) {
            currentCoroutineContext().ensureActive()
            val (docId, rel, depth) = queue.removeFirst()
            if (depth >= MAX_DEPTH) continue
            for (child in queryChildren(treeUri, docId)) {
                val childRel = joinRelative(rel, child.name)
                out += TreeNode(child.uri, childRel, child.isDir, child.hint)
                if (child.isDir) queue.addLast(Triple(child.docId, childRel, depth + 1))
            }
        }
        return out
    }

    private data class RawChild(val docId: String, val uri: Uri, val name: String, val isDir: Boolean, val hint: TreeHint)

    private fun queryChildren(treeUri: Uri, parentDocId: String): List<RawChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<RawChild>()
        val treeKey = "${treeUri.authority}:${DocumentsContract.getTreeDocumentId(treeUri)}"
        runCatching {
            cr.query(childrenUri, columns, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val timeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIndex) ?: continue
                    val rawName = cursor.getString(nameIndex) ?: continue
                    val mime = cursor.getString(mimeIndex)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    val modified = if (timeIndex >= 0 && !cursor.isNull(timeIndex)) cursor.getLong(timeIndex) else -1L
                    result += RawChild(
                        docId = docId,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name = rawName,
                        isDir = isDir,
                        hint = TreeHint(
                            name = rawName,
                            mimeType = if (isDir) null else mime,
                            size = size,
                            lastModified = modified,
                            signature = "tree:$treeKey:$docId:$size:$modified",
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "读取文件夹内容失败: $parentDocId", it) }
        return result
    }

    // ---------------------------------------------------------------- 元信息

    private fun displayNameOf(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "导入文件" }
    }

    private fun mimeOf(uri: Uri): String? {
        if (uri.scheme == "file") return FileKind.guessMime(uri.lastPathSegment.orEmpty())
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return FileKind.guessMime(cursor.getString(0))
            }
        }
        return runCatching { cr.getType(uri) }.getOrNull()
    }

    /** 一次性授权（未持久化）的 uri 只能退化为“名字 + 大小”判重。 */
    private fun fallbackSignature(uri: Uri, name: String): String? {
        val size = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
        return if (size > 0) "one-off:$name:$size" else null
    }

    private fun persistReadPermission(treeUri: Uri, withWrite: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (withWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching {
            cr.takePersistableUriPermission(treeUri, flags)
        }.onFailure { Log.w(TAG, "无法持久化文件夹授权", it) }
        prefs.watchedTreeUri = treeUri.toString()
        prefs.watchedTreeLabel = treeLabel(treeUri)
    }

    /** tree uri 的最后一段形如 primary:Documents/春节，取最末一级作为文件夹名。 */
    private fun treeLabel(treeUri: Uri): String {
        val tail = treeUri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return tail.substringAfterLast(':').ifBlank { "导入的文件夹" }
    }

    private companion object {
        const val TAG = "ImportEngine"
        const val COPY_BUFFER = 128 * 1024
        const val PROGRESS_STEP = 1L shl 20 // 每 1 MB 上报一次，避免过度重组
        const val MIN_FREE_MARGIN = 64L * 1024 * 1024
        const val MAX_DIRS = 20_000
        const val MAX_DEPTH = 24
    }
}

private sealed interface ImportOutcome
private data class Imported(val bytes: Long) : ImportOutcome
private object Skipped : ImportOutcome

internal fun Throwable.userMessage(): String = when (this) {
    is FileNotFoundException -> "源文件已被移动或删除"
    is SecurityException -> "系统未授予该文件的读取权限，请重新选择"
    is IOException -> message ?: "读写失败"
    else -> message ?: "操作失败"
}
