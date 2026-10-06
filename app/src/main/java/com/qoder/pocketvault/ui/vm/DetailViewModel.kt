package com.qoder.pocketvault.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qoder.pocketvault.AppGraph
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.core.joinRelative
import com.qoder.pocketvault.data.ArchiveCapability
import com.qoder.pocketvault.data.ArchiveNames
import com.qoder.pocketvault.data.ArchiveFormat
import com.qoder.pocketvault.data.ArchiveItem
import com.qoder.pocketvault.data.ConflictPolicy
import com.qoder.pocketvault.data.ExtractPlan
import com.qoder.pocketvault.data.ExtractSummary
import com.qoder.pocketvault.data.WrongArchivePasswordException
import com.qoder.pocketvault.data.db.ROOT_ID
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.data.userMessage
import com.qoder.pocketvault.util.FileBridge
import com.qoder.pocketvault.util.PreviewContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 包内单条目的预览结果：图片直接显示，文本走编码识别，其他类型只能提示"提取后可看"。 */
sealed class EntryPreview {
    data class Image(val file: java.io.File) : EntryPreview()
    data class Text(val body: String) : EntryPreview()
    data class NeedExtract(val file: java.io.File) : EntryPreview()
    data class Error(val message: String) : EntryPreview()
}

/** 解压落点：同名新文件夹是默认，避免把几百个文件直接倒在当前目录。 */
enum class ExtractTarget { NEW_FOLDER, CURRENT_FOLDER, SPECIFIC }

data class ArchiveUiState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val items: List<ArchiveItem> = emptyList(),
    val needsPassword: Boolean = false,
    val error: String? = null,
    val extractedCount: Int = 0,
    val progress: Pair<Long, Long>? = null,
    /** UNSUPPORTED / PASSWORD_REQUIRED 时界面要走另一条路，不能再显示空白卡片。 */
    val capability: ArchiveCapability? = null,
    val unsupportedReason: String? = null,
    /** 解压前的计划（条目数、总量、冲突数、空间够不够）。 */
    val plan: ExtractPlan? = null,
    /** 最近一次解压结果，用于"进入该文件夹"与失败后的一键清理。 */
    val lastSummary: ExtractSummary? = null,
)

/** 单个条目的详情：预览、压缩包内容、以及针对该条目的操作。 */
class DetailViewModel(
    private val graph: AppGraph,
    private val entryId: Long,
) : ViewModel() {

    private val repo = graph.repo

    private val _entry = MutableStateFlow<VaultEntry?>(null)
    val entry: StateFlow<VaultEntry?> = _entry

    private val _preview = MutableStateFlow<PreviewContent?>(null)
    val preview: StateFlow<PreviewContent?> = _preview

    private val _archive = MutableStateFlow(ArchiveUiState())
    val archive: StateFlow<ArchiveUiState> = _archive

    /** 当前交给界面看的那一份预览临时文件（缓存目录里的，不属于库内容）。 */
    private var lastPreview: File? = null

    /** 上次会话被杀时可能留下过预览文件，进入压缩包界面时先扫一次。 */
    private var previewCacheSwept = false

    private val _folders = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** 库内可当解压目标的文件夹。 */
    val folders: StateFlow<List<Pair<String, String>>> = _folders

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun reload() {
        viewModelScope.launch {
            val loaded = runCatching { repo.entryById(entryId) }.getOrNull()
            _entry.value = loaded
            if (loaded == null) {
                _message.value = "该项目已不存在"
                return@launch
            }
            _preview.value = runCatching { graph.preview.load(loaded) }.getOrDefault(PreviewContent.None)
            val file = physicalFile()
            if (loaded.kind == FileKind.ARCHIVE && file != null) {
                openArchive(file, null)
            } else {
                _archive.value = ArchiveUiState(loaded = true)
            }
        }
    }

    fun physicalFile(): File? = _entry.value?.let { runCatching { repo.physicalFile(it) }.getOrNull() }

    /** 解压目标候选：库内已有文件夹，外加"文件库根目录"。 */
    fun loadFolderChoices() {
        if (_folders.value.isNotEmpty()) return
        viewModelScope.launch {
            val paths = runCatching { repo.folderPaths() }.getOrDefault(emptyList())
            _folders.value = (listOf("" to "文件库根目录") + paths.map { it to it.replace("/", " ›") })
                .distinctBy { it.first }
        }
    }

    /** 包所在目录（解压默认落在这里面，或建同名新文件夹）。 */
    fun containingFolder(): String =
        _entry.value?.relativePath?.trim('/')?.substringBeforeLast('/', "").orEmpty()

    /**
     * 打开包并按能力分流：不支持的格式给明确原因，不再显示空白卡片；
     * 需要密码的直接把密码框摆出来（不用先点「读取列表」）。
     */
    private fun openArchive(file: File, password: String?) {
        val capability = graph.archives.supportFor(file)
        when (capability) {
            ArchiveCapability.UNSUPPORTED -> {
                _archive.value = ArchiveUiState(
                    loaded = true,
                    capability = capability,
                    unsupportedReason = ArchiveFormat.unsupportedReason(file.name),
                )
            }

            ArchiveCapability.PASSWORD_REQUIRED -> {
                _archive.value = ArchiveUiState(
                    loaded = true,
                    capability = capability,
                    needsPassword = true,
                    error = if (password != null) "密码不对，或者这个包用了不支持的加密方式" else null,
                )
            }

            ArchiveCapability.EXTRACTABLE -> listArchive(file, password, capability)
        }
    }

    fun listArchive(password: String?) {
        val file = physicalFile() ?: return
        openArchive(file, password)
    }

    private fun listArchive(file: File, password: String?, capability: ArchiveCapability) {
        _archive.value = _archive.value.copy(loading = true, error = null, capability = capability)
        viewModelScope.launch {
            val outcome = runCatching { graph.archives.list(file, password) }
            outcome.fold(
                onSuccess = { items ->
                    _archive.value = _archive.value.copy(
                        loaded = true,
                        loading = false,
                        items = items,
                        needsPassword = items.any { it.encrypted } && password == null,
                        error = null,
                    )
                },
                onFailure = { error ->
                    _archive.value = _archive.value.copy(
                        loaded = true,
                        loading = false,
                        needsPassword = password == null,
                        capability = if (error is WrongArchivePasswordException) {
                            ArchiveCapability.PASSWORD_REQUIRED
                        } else {
                            capability
                        },
                        error = error.userMessage(),
                    )
                },
            )
        }
    }

    /**
     * 解压前的一轮核对：真实落点、同名项数量、磁盘空间够不够。
     * 结果交给界面决定要不要弹「合并/改名/取消」与「覆盖/保留两者/跳过」。
     */
    fun planExtract(target: ExtractTarget, specificFolder: String?, password: String?, onReady: (ExtractPlan) -> Unit) {
        val file = physicalFile() ?: return
        val parent = containingFolder()
        val dest = when (target) {
            ExtractTarget.CURRENT_FOLDER, ExtractTarget.NEW_FOLDER -> parent
            ExtractTarget.SPECIFIC -> VaultPaths.normalizeRelative(specificFolder ?: parent).ifEmpty { parent }
        }
        // 与 extract() 的落点保持一致：同名新文件夹（无论新建还是合并）都落进 stem 里，
        // 选「自动改名」时落点是空目录，冲突数天然是 0，界面用 suggestedFolder 说明。
        val landing = if (target == ExtractTarget.NEW_FOLDER) joinRelative(parent, archiveStem(file.name)) else dest
        val listed = _archive.value.items.ifEmpty { null }
        viewModelScope.launch {
            val plan = runCatching {
                graph.archives.plan(file, dest, password, graph.paths.usableBytes(), listed, landing)
            }
            plan.fold(
                onSuccess = { value ->
                    _archive.value = _archive.value.copy(plan = value)
                    onReady(value)
                },
                onFailure = { error ->
                    _archive.value = _archive.value.copy(error = error.userMessage())
                    _message.value = error.userMessage()
                },
            )
        }
    }

    fun extract(
        target: ExtractTarget,
        specificFolder: String?,
        password: String?,
        policy: ConflictPolicy,
        mergeIntoExisting: Boolean,
    ) {
        if (_busy.value) return
        val file = physicalFile() ?: return
        val parent = containingFolder()
        // 占位必须在第一个挂起点之前，否则极快的两次点击都能通过上面的 _busy 检查
        _busy.value = true
        viewModelScope.launch {
            val decision = runCatching {
                when (target) {
                    ExtractTarget.NEW_FOLDER -> {
                        val preferred = joinRelative(parent, archiveStem(file.name))
                        val name = if (!mergeIntoExisting && graph.archives.folderExists(preferred)) {
                            graph.archives.freeFolderPath(parent, archiveStem(file.name)).substringAfterLast('/')
                        } else {
                            archiveStem(file.name)
                        }
                        parent to name
                    }

                    ExtractTarget.CURRENT_FOLDER -> parent to null

                    ExtractTarget.SPECIFIC ->
                        VaultPaths.normalizeRelative(specificFolder ?: parent).ifEmpty { parent } to null
                }
            }
            decision.fold(
                onSuccess = { (dest, folderName) -> runExtraction(file, dest, folderName, password, policy) },
                onFailure = {
                    _busy.value = false
                    _message.value = it.userMessage()
                },
            )
        }
    }

    private fun runExtraction(
        file: File,
        dest: String,
        folderName: String?,
        password: String?,
        policy: ConflictPolicy,
    ) {
        _archive.value = _archive.value.copy(progress = 0L to 0L, error = null)
        viewModelScope.launch {
            val result = runCatching {
                graph.archives.extractToVault(
                    archive = file,
                    destDirRelativePath = dest,
                    password = password,
                    onProgress = { written, total ->
                        _archive.value = _archive.value.copy(progress = written to total)
                    },
                    policy = policy,
                    folderName = folderName,
                )
            }
            _busy.value = false
            result.fold(
                onSuccess = { summary ->
                    _archive.value = _archive.value.copy(
                        progress = null,
                        extractedCount = summary.written,
                        lastSummary = summary,
                    )
                    _message.value = summaryMessage(summary)
                },
                onFailure = { error ->
                    _archive.value = _archive.value.copy(progress = null, error = error.userMessage())
                    _message.value = error.userMessage()
                },
            )
        }
    }

    private fun summaryMessage(summary: ExtractSummary): String = buildString {
        append("已解压 ${summary.written} 个文件到 ${summary.destRelativePath.ifEmpty { "根目录" }}")
        if (summary.renamed > 0) append("；改名 ${summary.renamed} 项")
        if (summary.overwritten > 0) append("；覆盖 ${summary.overwritten} 项（原件已移入回收站，可还原）")
        if (summary.skipped > 0) append("；跳过 ${summary.skipped} 项")
        summary.refusalNote?.let { append("（$it）") }
        if (summary.skippedLinks > 0) append("；链接类 ${summary.skippedLinks} 项未落盘")
        if (summary.partial) append("；中途中断：${summary.error}")
    }

    /** 解压中断后清掉本次产生的半成品，走库的删除链路而不是直接删文件。 */
    fun cleanupPartialExtraction() {
        val summary = _archive.value.lastSummary ?: return
        if (!summary.partial) return
        viewModelScope.launch {
            val folder = summary.createdFolder
            val targets = if (folder != null) {
                listOfNotNull(runCatching { repo.entryAt(folder) }.getOrNull()?.id)
            } else {
                summary.createdEntryIds
            }
            runCatching { repo.deletePermanently(targets) }
            _message.value = if (targets.isEmpty()) "没有需要清理的文件" else "已清理本次解压的 ${targets.size} 项"
            _archive.value = _archive.value.copy(lastSummary = null, extractedCount = 0)
        }
    }

    /** 返回可以直接跳转的目录相对路径；没有新建目录时返回所在目录。 */
    private fun openableDestination(): String? = _archive.value.lastSummary?.destRelativePath

    /** 解压完直接进目标文件夹看结果（导航只认行 id，所以这里先换算）。 */
    fun openDestination(navigate: (Long) -> Unit) {
        val dest = openableDestination() ?: return
        viewModelScope.launch {
            val id = if (dest.isEmpty()) ROOT_ID else runCatching { repo.entryAt(dest)?.id ?: ROOT_ID }.getOrDefault(ROOT_ID)
            navigate(id)
        }
    }

    /** 只提取包里的一个条目（条目行点击）。 */
    fun extractEntry(item: ArchiveItem, password: String?) {
        if (_busy.value) return
        val file = physicalFile() ?: return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching {
                graph.archives.extractEntryToVault(file, item, containingFolder(), password?.ifBlank { null })
            }
            _busy.value = false
            outcome.fold(
                onSuccess = { entry ->
                    _message.value = entry?.let { "已提取 ${it.name}" } ?: "该条目已被跳过"
                },
                onFailure = { error -> _message.value = error.userMessage() },
            )
        }
    }

    /** 单条目预览：先解到缓存临时文件，图片与文本能直接看；其他类型提示先提取。 */
    fun previewEntry(item: ArchiveItem, password: String?, onReady: (EntryPreview) -> Unit) {
        val file = physicalFile() ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            if (!previewCacheSwept) {
                previewCacheSwept = true
                // 上次会话被杀时可能留下过预览文件，先整目录扫一次
                runCatching { graph.archives.clearPreviewCache() }
            }
            // 上一次预览的文件已经不会再被界面引用了，先收掉，缓存里最多只留当前这一份
            lastPreview?.let { runCatching { it.delete() } }
            lastPreview = null
            val result = runCatching {
                graph.archives.extractToTemp(file, item, password?.ifBlank { null }, item.displayName)
            }
            _busy.value = false
            val temp = result.getOrElse {
                onReady(EntryPreview.Error(it.userMessage()))
                return@launch
            }
            if (temp == null) {
                onReady(EntryPreview.Error("这一类条目不能预览"))
                return@launch
            }
            lastPreview = temp
            val kind = FileKind.classify(item.displayName, null)
            onReady(
                when (kind) {
                    FileKind.IMAGE -> EntryPreview.Image(temp)
                    FileKind.DOCUMENT, FileKind.OTHER -> {
                        // 用 TextBook 解一次，编码识别与阅读器一致；只取前面一小段
                        val text = runCatching {
                            com.qoder.pocketvault.util.TextBookLoader.load(temp).paragraphsOf(0).take(120).joinToString("\n")
                        }.getOrNull()
                        if (text.isNullOrBlank()) EntryPreview.NeedExtract(temp)
                        else EntryPreview.Text(text.take(20_000))
                    }

                    else -> EntryPreview.NeedExtract(temp)
                },
            )
        }
    }

    private fun archiveStem(name: String): String = ArchiveNames.stem(name)

    fun openWith(context: Context) {
        val entry = _entry.value ?: return
        val file = physicalFile() ?: return
        _message.value = FileBridge.openWith(context, file, entry)
    }

    fun share(context: Context) {
        val entry = _entry.value ?: return
        val file = physicalFile() ?: return
        _message.value = FileBridge.share(context, file, entry)
    }

    fun exportToPublic() = mutate("已导出到公共目录，相册/文件管理器现在可见") {
        graph.exporter.exportToPublicLibrary(listOf(requireNotNull(_entry.value) { "项目不存在" }))
        null
    }

    /** 移动到用户选的任意文件夹：校验字节数后才从本应用删除，成功则回调退出页面。 */
    fun moveToTree(treeUri: android.net.Uri, onMoved: () -> Unit) {
        val entry = _entry.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching { graph.exporter.moveToTree(treeUri, listOf(entry)) }
            _busy.value = false
            outcome.fold(
                onSuccess = { result ->
                    if (result.moved > 0 && result.unresolved.isEmpty()) {
                        _message.value = "已移动到所选文件夹，本应用内不再保留"
                        onMoved()
                    } else {
                        _message.value = "有 ${result.unresolved.size} 项未通过校验，已保留在文件库：${result.unresolved.joinToString("、")}"
                        reload()
                    }
                },
                onFailure = { error -> _message.value = error.userMessage() }
            )
        }
    }

    fun rename(newName: String) = mutate("已重命名为 $newName") {
        val updated = repo.rename(entryId, newName)
        _entry.value = updated
        null
    }

    fun setFavorite(favorite: Boolean) {
        viewModelScope.launch {
            runCatching { repo.setFavorite(entryId, favorite) }
            _entry.value = _entry.value?.copy(favorite = favorite)
        }
    }

    /** 删除成功后回调退出页面。 */
    fun trash(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.trash(listOf(entryId)) }
                .onSuccess { onDeleted() }
                .onFailure { _message.value = it.userMessage() }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 离开详情页就把预览缓存清掉：这些文件不属于库内容，留着只是占缓存。 */
    override fun onCleared() {
        runCatching { lastPreview?.delete() }
        lastPreview = null
        graph.ioScope.launch { runCatching { graph.archives.clearPreviewCache() } }
    }

    private fun mutate(successHint: String, block: suspend () -> String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching { block() }
            _busy.value = false
            _message.value = outcome.fold(onFailure = { error -> error.userMessage() }, onSuccess = { it })
                ?: successHint
            reload()
        }
    }
}
