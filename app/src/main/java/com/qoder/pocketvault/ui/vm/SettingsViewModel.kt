package com.qoder.pocketvault.ui.vm

import android.content.UriPermission
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qoder.pocketvault.AppGraph
import com.qoder.pocketvault.core.VaultRootMode
import com.qoder.pocketvault.data.VerifyReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class SettingsState(
    val rootMode: VaultRootMode = VaultRootMode.INTERNAL,
    val internalPath: String = "",
    val externalPath: String = "",
    val currentPath: String = "",
    val usableBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val isEmptyVault: Boolean = true,
    val retentionDays: Int = 30,
    val trees: List<UriPermission> = emptyList(),
    val thumbCacheBytes: Long = 0L,
    val message: String? = null,
)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    fun reload() {
        viewModelScope.launch {
            val thumbDir = File(graph.thumbs.cacheDirPath())
            _state.value = SettingsState(
                rootMode = graph.paths.mode(),
                internalPath = graph.paths.pathFor(VaultRootMode.INTERNAL),
                externalPath = graph.paths.pathFor(VaultRootMode.EXTERNAL_SANDBOX),
                currentPath = graph.rootPath(),
                usableBytes = graph.paths.usableBytes(),
                usedBytes = runCatching { graph.repo.usedBytes() }.getOrDefault(0L),
                isEmptyVault = runCatching { graph.repo.isEmpty() }.getOrDefault(true),
                retentionDays = graph.prefs.trashRetentionDays,
                trees = graph.importer.persistedTrees(),
                thumbCacheBytes = if (thumbDir.isDirectory) thumbDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L,
            )
        }
    }

    fun switchRoot(mode: VaultRootMode) {
        viewModelScope.launch {
            val empty = runCatching { graph.repo.isEmpty() }.getOrDefault(false)
            val ok = graph.paths.switchRoot(mode, isEmpty = empty)
            _state.value = _state.value.copy(
                message = if (ok) "私有目录已切换，之后导入的内容会写入新位置" else "文件库里还有内容，请先全部导出或清空回收站后再切换",
            )
            reload()
        }
    }

    fun setRetention(days: Int) {
        graph.prefs.trashRetentionDays = days
        _state.value = _state.value.copy(retentionDays = days, message = "回收站保留期已设为 $days 天")
    }

    fun forgetTree(uri: Uri) {
        graph.importer.forgetTree(uri)
        reload()
    }

    /** 重新扫描同一个来源文件夹；已存在且未变化的条目会被跳过。 */
    fun resyncTree(uri: Uri) {
        graph.importer.importTree(uri, "", rememberFolder = false)
        _state.value = _state.value.copy(message = "已开始重新同步，未变化的文件会自动跳过")
    }

    fun clearThumbCache() {
        graph.thumbs.clearCache()
        _state.value = _state.value.copy(message = "已清空缩略图缓存", thumbCacheBytes = 0L)
    }

    /** 手动重建索引：对账磁盘与数据库，补录外部写入的文件、清理失效记录。 */
    fun rescanIndex() {
        viewModelScope.launch {
            val report: VerifyReport = runCatching { graph.rescan() }.getOrElse {
                _state.value = _state.value.copy(message = "对账失败：${it.message}")
                return@launch
            }
            _state.value = _state.value.copy(message = report.summary.ifBlank { "索引与磁盘一致" })
            reload()
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
