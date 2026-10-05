package com.qoder.pocketvault.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.data.StorageReport
import com.qoder.pocketvault.data.db.ListOptions
import com.qoder.pocketvault.data.db.SortField
import com.qoder.pocketvault.ui.LocalNavigator
import com.qoder.pocketvault.ui.components.FilterChip
import com.qoder.pocketvault.ui.components.SelectionAction
import com.qoder.pocketvault.ui.components.SelectionMenuItem
import com.qoder.pocketvault.ui.viewer.viewerSupports
import com.qoder.pocketvault.ui.vm.FolderViewModel
import com.qoder.pocketvault.ui.vm.LibraryTab
import com.qoder.pocketvault.ui.vm.LibraryViewModel
import com.qoder.pocketvault.ui.vm.SearchViewModel
import com.qoder.pocketvault.ui.vm.TrashViewModel
import com.qoder.pocketvault.ui.vm.ViewMode

private val ALL_TABS = LibraryTab.values().toList()

private val SortField.label: String
    get() = when (this) {
        SortField.NAME -> "按名称"
        SortField.SIZE -> "按大小"
        SortField.MODIFIED -> "按修改时间"
        SortField.IMPORTED -> "按导入时间"
    }

private fun Modifier.tappable(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/** 列表点击的分流：能用应用内查看器打开的直接进查看器，其余进详情页。 */
private fun openInViewer(entry: com.qoder.pocketvault.data.db.VaultEntry): Boolean = viewerSupports(entry)

// ------------------------------------------------------------------ 类型分区

@Composable
fun LibraryScreen(vm: LibraryViewModel) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    BrowserHost(
        vm = vm,
        entries = entries,
        showPath = true,
        asGrid = tab.asGrid,
        header = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ALL_TABS.forEach { option ->
                        FilterChip(label = option.label, selected = option == tab, onClick = { vm.select(option) })
                    }
                }
                report?.let { StorageSummary(it) }
            }
        },
        emptyTitle = if (tab == LibraryTab.RECENTS) "还没有导入过内容" else "${tab.label}里还没有内容",
        emptyHint = "点右下角「导入」选择文件或整个文件夹；导入后的内容只在本应用里可见。",
        onOpen = { entry ->
            if (entry.isFolder) navigator?.openFolder(entry.id)
            else if (openInViewer(entry)) navigator?.openViewer(entry.id)
            else navigator?.openDetail(entry.id)
        },
    )
}

@Composable
private fun StorageSummary(report: StorageReport) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("已收纳 ${report.fileCount} 个文件 · ${formatBytes(report.usedBytes)}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (report.perKind.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    report.perKind.forEach { stat ->
                        Text(
                            "${stat.kind.label} ${stat.itemCount} · ${formatBytes(stat.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "私有目录剩余 ${formatBytes(report.freeBytes)}，系统相册与文件管理器看不到这里的内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// ------------------------------------------------------------------ 目录浏览

@Composable
fun FolderScreen(vm: FolderViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val breadcrumbs by vm.breadcrumbs.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val path by vm.currentDir.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var sortMenu by remember { mutableStateOf(false) }

    // 与系统文件管理器一致：进入子目录后按返回键先回上一级，回到根目录才退出页面
    androidx.activity.compose.BackHandler(enabled = path.isNotEmpty()) { vm.goUp() }

    BrowserHost(
        vm = vm,
        entries = entries,
        showPath = false,
        asGrid = viewMode == ViewMode.GRID,
        header = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (breadcrumbs.size > 1) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "上一级",
                            modifier = Modifier
                                .tappable { if (!vm.goUp()) navigator?.goBack() }
                                .padding(horizontal = 6.dp),
                        )
                    }
                    breadcrumbs.forEachIndexed { index, (path, label) ->
                        Text(
                            text = if (index == breadcrumbs.lastIndex) label else "$label ›",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (index == breadcrumbs.lastIndex) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .tappable { vm.open(path) }
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${entries.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        Text(
                            "排序：${options.field.label}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .tappable { sortMenu = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortField.values().forEach { field ->
                                DropdownMenuItem(
                                    text = {
                                        val arrow = if (options.field != field) "" else if (options.ascending) " ↑" else " ↓"
                                        Text(field.label + arrow)
                                    },
                                    onClick = {
                                        sortMenu = false
                                        vm.setOptions(
                                            if (options.field == field) options.copy(ascending = !options.ascending)
                                            else ListOptions(field = field, ascending = field == SortField.NAME)
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Icon(
                        Icons.Filled.GridView,
                        contentDescription = if (viewMode == ViewMode.GRID) "切换为列表" else "切换为网格",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .tappable(vm::toggleViewMode)
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        },
        emptyTitle = "这个文件夹是空的",
        emptyHint = "可以用右下角的「导入」把文件或整个文件夹放进来，目录层级会原样保留。",
        onOpen = { entry ->
            if (entry.isFolder) vm.open(entry.relativePath)
            else if (openInViewer(entry)) navigator?.openViewer(entry.id)
            else navigator?.openDetail(entry.id)
        },
    )
}

// ------------------------------------------------------------------ 搜索

@Composable
fun SearchScreen(vm: SearchViewModel) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.kindFilter.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    BrowserHost(
        vm = vm,
        entries = results,
        showPath = true,
        asGrid = false,
        header = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::updateQuery,
                    label = { Text("在文件库内搜索名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip("全部类型", filter == null) { vm.setKindFilter(null) }
                    listOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO, FileKind.DOCUMENT, FileKind.ARCHIVE).forEach { kind ->
                        FilterChip(kind.label, filter == kind) { vm.setKindFilter(kind) }
                    }
                }
            }
        },
        emptyTitle = if (query.isBlank()) "输入名称开始搜索" else "没有找到「$query」",
        emptyHint = "搜索覆盖所有层级的文件名；输入完整关键字还能按类型筛选。",
        onOpen = { entry ->
            if (openInViewer(entry)) navigator?.openViewer(entry.id) else navigator?.openDetail(entry.id)
        },
    )
}

// ------------------------------------------------------------------ 回收站

@Composable
fun TrashScreen(vm: TrashViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()

    BrowserHost(
        vm = vm,
        entries = entries,
        showPath = true,
        asGrid = false,
        showImportFab = false,
        selectionMenu = listOf(
            SelectionMenuItem("恢复原位置", SelectionAction.RESTORE),
            SelectionMenuItem("彻底删除", SelectionAction.DELETE_FOREVER),
            SelectionMenuItem("清空回收站", SelectionAction.EMPTY_TRASH),
        ),
        onMenuAction = { action ->
            when (action) {
                SelectionAction.RESTORE -> vm.restoreSelected()
                SelectionAction.DELETE_FOREVER -> vm.deleteForeverSelected()
                SelectionAction.EMPTY_TRASH -> vm.emptyTrash()
                else -> Unit
            }
        },
        header = {
            Text(
                "回收站里的内容仍然保存在私有目录，超过保留天数会被自动清除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
        emptyTitle = "回收站是空的",
        emptyHint = "删除的项目会先留在这里，可以随时恢复。",
        onOpen = { },
    )
}
