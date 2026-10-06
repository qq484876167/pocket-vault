package com.qoder.pocketvault.ui.vm

import com.qoder.pocketvault.core.VaultPrefs
import com.qoder.pocketvault.data.FolderRow
import com.qoder.pocketvault.data.VaultRepository
import com.qoder.pocketvault.ui.components.FolderPickerPorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 目标目录选择器的后端：一层一层读子目录、记最近用过的目标、在选择器里直接建目录。
 *
 * 库内移动、库内复制、解压目标三个入口共用同一份实现，所以逻辑放在这里而不是各写一遍。
 * 历史只存路径字符串在 prefs（不是业务状态），读的时候要过滤掉已经不存在的目录。
 */
class FolderTargetBook(
    private val repo: VaultRepository,
    private val prefs: VaultPrefs,
    private val scope: CoroutineScope,
) {

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            _history.value = prefs.folderTargets.filter { exists(it) }
        }
    }

    /** 根目录永远存在；其余要能在索引里查到且是 ACTIVE 的文件夹。 */
    private suspend fun exists(path: String): Boolean = runCatching {
        path.isEmpty() || repo.entryAt(path)?.isFolder == true
    }.getOrDefault(false)   // 路径本身不合法（老数据、手工写坏的 prefs）也算"这条历史失效"

    suspend fun rows(path: String): List<FolderRow>? = runCatching { repo.folderRows(path) }.getOrNull()

    /** 返回新建目录的逻辑路径；失败返回 null 让界面说"没能创建"。 */
    suspend fun create(parent: String, name: String): String? =
        runCatching { repo.createFolder(parent, name).relativePath }.getOrNull()

    /** 一次动作完成后记下这个目标，最近 5 条、新在前（由 prefs 截断去重）。 */
    fun remember(path: String) {
        prefs.folderTargets = listOf(path) + prefs.folderTargets.filterNot { it == path }
        _history.value = prefs.folderTargets
    }

    /** 组件里就地改动历史（点到失效条目、清空）后写回。 */
    fun save(targets: List<String>) {
        prefs.folderTargets = targets
        _history.value = targets
    }

    fun ports(): FolderPickerPorts = FolderPickerPorts(
        loadChildren = ::rows,
        createFolder = ::create,
        initialHistory = _history.value,
        onHistoryChange = ::save,
    )

    /**
     * 移动这些文件夹时哪些目录不能当目标：它们自己 + 它们的所有后代。
     * 复制不需要（复制到自己是产生一份副本）。
     *
     * 一次把全库目录路径读出来做前缀判断，不逐层递归查库；
     * [VaultRepository.move] 里那条"不能把文件夹移动到它自己里面"仍然保留作兜底。
     */
    suspend fun forbiddenForMove(sources: List<String>): Map<String, String> {
        val folders = sources.filter { it.isNotEmpty() }.distinct()
        if (folders.isEmpty()) return emptyMap()
        val all = runCatching { repo.folderPaths() }.getOrDefault(emptyList())
        val blocked = LinkedHashMap<String, String>()
        folders.forEach { source ->
            if (source == "") return@forEach
            all.forEach { candidate ->
                when {
                    candidate == source -> blocked[candidate] = "不能移动到它自己里面（这正是要移动的文件夹）"
                    candidate.startsWith("$source/") -> blocked[candidate] = "不能移动到它自己里面（它在要移动的文件夹里面）"
                }
            }
        }
        return blocked
    }
}
