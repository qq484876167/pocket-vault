package com.qoder.pocketvault.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qoder.pocketvault.AppGraph
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.data.ArchiveCompression
import com.qoder.pocketvault.data.ArchiveNames
import com.qoder.pocketvault.core.breadcrumbOf
import com.qoder.pocketvault.data.StorageReport
import com.qoder.pocketvault.data.VaultRepository
import com.qoder.pocketvault.data.db.ListOptions
import com.qoder.pocketvault.data.db.ROOT_ID
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ViewMode { LIST, GRID }

/**
 * 列表/网格页面共用的选择态与批量操作。子类只负责“当前看哪一批条目、默认落在哪个目录”。
 */
abstract class BrowseViewModel(protected val graph: AppGraph) : ViewModel() {

    protected val repo: VaultRepository = graph.repo

    /** 目标目录选择器的后端：逐层读目录、记历史、在选择器里建目录。 */
    val folderTargets = FolderTargetBook(repo, graph.prefs, viewModelScope)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    protected val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 批量操作的默认目录（移动/复制/解压/压缩产物的落点）。 */
    abstract val currentDir: StateFlow<String>

    protected open fun onSelectionCleared() {
        _selection.value = emptySet()
    }

    fun showMessage(text: String?) {
        if (!text.isNullOrBlank()) _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun toggleSelect(id: Long) {
        _selection.update { if (id in it) it - id else it + id }
    }

    fun selectAll(ids: List<Long>) {
        _selection.value = ids.toSet()
    }

    fun clearSelection() = onSelectionCleared()

    protected fun selectedIds(): List<Long> = _selection.value.toList()

    protected val hasSelection: Boolean get() = _selection.value.isNotEmpty()

    /** 统一的“忙碌态 + 异常转中文提示”包装，页面只需要显示 message。 */
    protected fun runAction(successHint: String? = null, block: suspend () -> String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching { block() }.onFailure { it.rethrowIfCancelled() }
            _busy.value = false
            _message.value = outcome.fold(onFailure = { error -> error.userMessage() }, onSuccess = { it })
                ?: successHint
            onSelectionCleared()
        }
    }

    /**
     * 解压一个或一批压缩包：每个包独立成功失败，一个坏了不影响其余，
     * 最后汇总成一句提示。加密包需要密码时提示去详情页输密码（这里不弹密码框）。
     */
    fun extractArchives(
        picked: List<VaultEntry>,
        target: ExtractTarget,
        specific: String? = null,
        password: String? = null,
    ) = runAction {
        val archives = picked.filter { it.kind == FileKind.ARCHIVE }
        require(archives.isNotEmpty()) { "选中的项目里没有压缩包" }
        var done = 0
        var files = 0
        val failures = ArrayList<String>()
        for (archive in archives) {
            val file = runCatching { repo.physicalFile(archive) }
                .onFailure { it.rethrowIfCancelled() }.getOrNull()
            if (file == null) {
                failures += "${archive.name}（取不到文件）"
                continue
            }
            val parent = archive.relativePath.trim('/').substringBeforeLast('/', "")
            val dest = if (target == ExtractTarget.SPECIFIC) {
                VaultPaths.normalizeRelative(specific ?: parent)
            } else {
                parent
            }
            val folder = if (target == ExtractTarget.NEW_FOLDER) ArchiveNames.stem(archive.name) else null
            val summary = runCatching {
                graph.archives.extractToVault(
                    archive = file,
                    destDirRelativePath = dest,
                    password = password,
                    onProgress = { _, _ -> },
                    folderName = folder,
                )
            }
                .onFailure { it.rethrowIfCancelled() }
            summary.fold(
                onSuccess = { value ->
                    done++
                    files += value.written
                    if (value.partial) failures += "${archive.name}（中途失败：${value.error}）"
                    else if (value.skipped > 0 || value.renamed > 0) {
                        failures += "${archive.name}（跳过 ${value.skipped}、改名 ${value.renamed}" +
                            (value.refusalNote?.let { "，$it" } ?: "") + "）"
                    }
                },
                onFailure = { failures += "${archive.name}（${it.userMessage()}）" },
            )
        }
        if (target == ExtractTarget.SPECIFIC && done > 0) {
            folderTargets.remember(VaultPaths.normalizeRelative(specific.orEmpty()))
        }
        buildString {
            append("已解压 $done/${archives.size} 个压缩包，共 $files 个文件")
            if (failures.isNotEmpty()) {
                append("；${failures.size} 个没解完：")
                append(failures.take(3).joinToString("、"))
                if (failures.size > 3) append(" 等")
                append("。加密包请在详情页输密码")
            }
        }
    }

    fun newFolder(name: String) = runAction("已创建文件夹 $name") {
        repo.createFolder(currentDir.value, name)
        null
    }

    fun rename(id: Long, newName: String) = runAction("已重命名为 $newName") {
        repo.rename(id, newName)
        null
    }

    fun moveToSelected(destDir: String) = runAction {
        val count = repo.move(selectedIds(), destDir)
        if (count > 0) folderTargets.remember(destDir)
        "已移动 $count 项"
    }

    fun copyToSelected(destDir: String) = runAction {
        val count = repo.copyInto(selectedIds(), destDir)
        if (count > 0) folderTargets.remember(destDir)
        "已复制 $count 项到 ${destDir.ifBlank { "文件库根目录" }}"
    }

    fun trashSelected() = runAction("已移入回收站") {
        repo.trash(selectedIds())
        null
    }

    fun toggleFavorite(id: Long, favorite: Boolean) {
        viewModelScope.launch { runCatching { repo.setFavorite(id, favorite) } }
    }

    fun exportSelectedToPublic() = runAction {
        val entries = repo.entriesByIds(selectedIds())
        val count = graph.exporter.exportToPublicLibrary(entries)
        "已导出 $count 项到公共目录，相册与系统文件管理器现在可以看到它们"
    }

    fun exportSelectedToTree(treeUri: Uri) = runAction {
        val entries = repo.entriesByIds(selectedIds())
        val count = graph.exporter.exportToTree(treeUri, entries)
        "已导出 $count 项到所选文件夹（保留目录结构）"
    }

    fun compressSelected(zipName: String, password: String?, level: ArchiveCompression) =
        runAction("已生成 $zipName.zip") {
            val entries = repo.entriesByIds(selectedIds())
            require(entries.isNotEmpty()) { "请先选择要压缩的项目" }
            graph.archives.compressToVault(entries, zipName, currentDir.value, password, level)
            null
        }

    fun importFiles(uris: List<Uri>, deleteSource: Boolean) =
        graph.importer.importFiles(uris, currentDir.value, deleteSource)

    fun importFolder(treeUri: Uri, deleteSource: Boolean) =
        graph.importer.importTree(treeUri, currentDir.value, rememberFolder = true, deleteSource = deleteSource)

    /** 把选中条目移动到用户指定的任意文件夹；校验字节数后才从本应用删除。 */
    fun moveSelectedToTree(treeUri: Uri) = runAction {
        val entries = repo.entriesByIds(selectedIds())
        require(entries.isNotEmpty()) { "请先选择要移动的项目" }
        val outcome = graph.exporter.moveToTree(treeUri, entries)
        buildString {
            append("已移出 ${outcome.moved} 项，本应用内不再保留")
            if (outcome.unresolved.isNotEmpty()) {
                append("；${outcome.unresolved.size} 项未通过校验，已保留：")
                append(outcome.unresolved.take(3).joinToString("、"))
                if (outcome.unresolved.size > 3) append(" 等")
            }
        }
    }

}

// ------------------------------------------------------------------ 目录浏览

@OptIn(ExperimentalCoroutinesApi::class)
class FolderViewModel(graph: AppGraph, folderId: Long) : BrowseViewModel(graph) {

    private val _path = MutableStateFlow("")
    override val currentDir: StateFlow<String> = _path

    private val _options = MutableStateFlow(ListOptions())
    val options: StateFlow<ListOptions> = _options

    private val _viewMode = MutableStateFlow(ViewMode.LIST)
    val viewMode: StateFlow<ViewMode> = _viewMode

    val storageRoot: String = graph.rootPath()

    init {
        if (folderId != ROOT_ID) {
            viewModelScope.launch {
                val start = runCatching { repo.entryById(folderId) }.getOrNull()
                _path.value = if (start != null && start.isFolder) start.relativePath else ""
            }
        }
    }

    val entries: StateFlow<List<VaultEntry>> =
        combine(_path, _options) { path, options ->
            repo.observeFolder(path, options).catch { cause ->
                // ensureFolderPath 之类会在这里抛：宁可显示空列表加一条提示，
                // 也不让整个 stateIn 作用域被一次读取异常打死
                if (cause !is CancellationException) _message.value = "读取这个目录失败：${cause.userMessage()}"
                emit(emptyList())
            }
        }
            .flatMapLatest { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val breadcrumbs: StateFlow<List<Pair<String, String>>> = _path
        .map { breadcrumbOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf("" to "文件库"))

    /**
     * 当前目录的行 id，供"在本目录内搜索"传参用（路径不进取向参数，见 Navigator 注释）。
     * 查失败时保留上一次的值：直接退回根目录会把"本目录内搜索"悄悄变成全库搜索。
     */
    val folderId: StateFlow<Long> = _path
        .scan(ROOT_ID) { previous, path ->
            runCatching { repo.folderIdFor(path) }.onFailure { it.rethrowIfCancelled() }.getOrDefault(previous)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ROOT_ID)

    fun open(dirRelativePath: String) {
        _path.value = runCatching { VaultPaths.normalizeRelative(dirRelativePath) }.getOrDefault("")
        onSelectionCleared()
    }

    fun goUp(): Boolean {
        val current = _path.value
        if (current.isEmpty()) return false
        open(current.trim('/').substringBeforeLast('/', ""))
        return true
    }

    fun setOptions(newOptions: ListOptions) {
        _options.value = newOptions
    }

    fun toggleViewMode() {
        _viewMode.value = if (_viewMode.value == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
    }
}

// ------------------------------------------------------------------ 类型分区

enum class LibraryTab(val label: String, val kind: FileKind?) {
    RECENTS("最近", null),
    IMAGE("图片", FileKind.IMAGE),
    VIDEO("视频", FileKind.VIDEO),
    AUDIO("音频", FileKind.AUDIO),
    DOCUMENT("文档", FileKind.DOCUMENT),
    ARCHIVE("压缩包", FileKind.ARCHIVE),
    FAVORITES("收藏", null);

    val asGrid: Boolean get() = this == IMAGE || this == VIDEO
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(graph: AppGraph) : BrowseViewModel(graph) {

    private val _tab = MutableStateFlow(LibraryTab.RECENTS)
    val tab: StateFlow<LibraryTab> = _tab

    override val currentDir = MutableStateFlow("")

    val entries: StateFlow<List<VaultEntry>> = _tab.flatMapLatest { selected ->
        when (selected) {
            LibraryTab.RECENTS -> repo.observeRecent()
            LibraryTab.FAVORITES -> repo.observeFavorites()
            else -> repo.observeByKind(requireNotNull(selected.kind))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val report: StateFlow<StorageReport?> = repo.observeStats()
        .map<StorageReport, StorageReport?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(tab: LibraryTab) {
        _tab.value = tab
        onSelectionCleared()
    }
}

// ------------------------------------------------------------------ 搜索

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(graph: AppGraph, private val scopeFolderId: Long = ROOT_ID) : BrowseViewModel(graph) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _kindFilter = MutableStateFlow<FileKind?>(null)
    val kindFilter: StateFlow<FileKind?> = _kindFilter

    private val _scope = MutableStateFlow<VaultEntry?>(null)

    /** 搜索范围：null 表示全库。由目录页带 id 进来时才有值。 */
    val scope: StateFlow<VaultEntry?> = _scope

    override val currentDir = MutableStateFlow("")

    private val _results = MutableStateFlow<List<VaultEntry>>(emptyList())
    val results: StateFlow<List<VaultEntry>> = _results

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending

    init {
        if (scopeFolderId != ROOT_ID) {
            viewModelScope.launch {
                val folder = runCatching { repo.entryById(scopeFolderId) }.getOrNull()
                    ?.takeIf { it.isFolder }
                _scope.value = folder
                currentDir.value = folder?.relativePath ?: ""
            }
        }
        combine(_query, _kindFilter, _scope) { text, kind, scope ->
            Triple(text.trim(), kind, scope?.relativePath)
        }
            .distinctUntilChanged()
            .debounce(220)
            .onEach { (text, kind, within) ->
                _pending.value = text.isNotEmpty()
                val found = runCatching { repo.search(text, kind, within) }.onFailure { it.rethrowIfCancelled() }
                if (found.isFailure && text.isNotEmpty()) {
                    _message.value = "搜索失败：${found.exceptionOrNull()?.userMessage()}"
                }
                _results.value = found.getOrDefault(emptyList())
                _pending.value = false
            }
            .launchIn(viewModelScope)
    }

    fun updateQuery(text: String) {
        _query.value = text
    }

    fun setKindFilter(kind: FileKind?) {
        _kindFilter.value = kind
    }

    /** 清掉范围即回到全库搜索；范围来自路由，清空后不会自己长回来。 */
    fun clearScope() {
        _scope.value = null
        currentDir.value = ""
    }
}

// ------------------------------------------------------------------ 回收站

class TrashViewModel(graph: AppGraph) : BrowseViewModel(graph) {

    override val currentDir = MutableStateFlow("")

    val entries: StateFlow<List<VaultEntry>> = repo.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restoreSelected() = runAction("已恢复所选项目") {
        repo.restore(selectedIds())
        null
    }

    fun deleteForeverSelected() = runAction("已彻底删除") {
        repo.deleteForever(selectedIds())
        null
    }

    fun emptyTrash() = runAction("回收站已清空") {
        repo.emptyTrash()
        null
    }
}
