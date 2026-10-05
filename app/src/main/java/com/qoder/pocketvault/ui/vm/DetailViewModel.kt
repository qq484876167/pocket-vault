package com.qoder.pocketvault.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qoder.pocketvault.AppGraph
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.data.ArchiveItem
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.data.userMessage
import com.qoder.pocketvault.util.FileBridge
import com.qoder.pocketvault.util.PreviewContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ArchiveUiState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val items: List<ArchiveItem> = emptyList(),
    val needsPassword: Boolean = false,
    val error: String? = null,
    val extractedCount: Int = 0,
    val progress: Pair<Long, Long>? = null,
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
            val zipReadable = file != null &&
                graph.archives.supportFor(file) == com.qoder.pocketvault.data.ArchiveEngine.Support.ZIP_READABLE
            if (loaded.kind == FileKind.ARCHIVE && zipReadable) {
                listArchive(null)
            } else {
                _archive.value = ArchiveUiState(loaded = true)
            }
        }
    }

    fun physicalFile(): File? = _entry.value?.let { runCatching { repo.physicalFile(it) }.getOrNull() }

    fun listArchive(password: String?) {
        val file = physicalFile() ?: return
        _archive.value = _archive.value.copy(loading = true, error = null)
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
                        error = error.userMessage(),
                    )
                }
            )
        }
    }

    fun extract(password: String?) {
        if (_busy.value) return
        val file = physicalFile() ?: return
        val parent = _entry.value?.relativePath?.trim('/')?.substringBeforeLast('/', "").orEmpty()
        _busy.value = true
        _archive.value = _archive.value.copy(progress = 0L to 0L, error = null)
        viewModelScope.launch {
            val result = runCatching {
                graph.archives.extractToVault(file, parent, password) { written, total ->
                    _archive.value = _archive.value.copy(progress = written to total)
                }
            }
            _busy.value = false
            result.fold(
                onSuccess = { count ->
                    _archive.value = _archive.value.copy(progress = null, extractedCount = count)
                    _message.value = "已解压 $count 个文件到 $parent"
                },
                onFailure = { error ->
                    _archive.value = _archive.value.copy(progress = null, error = error.userMessage())
                    _message.value = error.userMessage()
                }
            )
        }
    }

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
