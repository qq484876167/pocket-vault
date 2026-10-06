package com.qoder.pocketvault.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.qoder.pocketvault.appGraph
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.core.formatDateTime
import com.qoder.pocketvault.core.formatDuration
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.ui.theme.KindColors
import java.io.File

fun kindIcon(kind: FileKind, name: String): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Filled.Folder
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.AUDIO -> Icons.Filled.Audiotrack
    FileKind.DOCUMENT -> if (FileKind.extensionOf(name) == "pdf") Icons.Filled.PictureAsPdf else Icons.AutoMirrored.Filled.InsertDriveFile
    FileKind.ARCHIVE -> Icons.Filled.Unarchive
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

fun kindColor(kind: FileKind): Color = when (kind) {
    FileKind.FOLDER -> KindColors.folder
    FileKind.IMAGE -> KindColors.image
    FileKind.VIDEO -> KindColors.video
    FileKind.AUDIO -> KindColors.audio
    FileKind.DOCUMENT -> KindColors.document
    FileKind.ARCHIVE -> KindColors.archive
    FileKind.OTHER -> KindColors.other
}

/** 私有目录里的物理文件；解析失败（例如刚被移入回收站）时为 null。 */
@Composable
fun rememberEntryFile(entry: VaultEntry?): File? {
    val context = LocalContext.current
    return remember(entry?.id, entry?.relativePath, entry?.state) {
        entry?.let { runCatching { context.appGraph().repo.physicalFile(it) }.getOrNull() }
    }
}

@Composable
fun Thumb(entry: VaultEntry, size: Int, modifier: Modifier = Modifier) {
    val file = rememberEntryFile(entry)
    // physicalFile() 只做字符串拼接、从不查磁盘，所以"索引有、文件已经没了"必须自己 stat 一次；
    // 再配合 onError 兜住"文件在但内容坏了"，否则这两种都是一片空白，看不出是图片
    val exists = remember(file?.path) { file != null && file.length() > 0L }
    var loadFailed by remember(file?.path) { mutableStateOf(false) }
    val canRender = entry.kind == FileKind.IMAGE && exists && !loadFailed
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (entry.isFolder) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else kindColor(entry.kind).copy(alpha = 0.14f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            canRender -> AsyncImage(
                model = file,
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { loadFailed = true },
            )

            else -> Icon(
                imageVector = kindIcon(entry.kind, entry.name),
                contentDescription = entry.kind.label,
                tint = kindColor(entry.kind),
                modifier = Modifier.size((size * 0.52f).dp),
            )
        }
    }
}

/** 一行一个条目，样式对齐系统文件管理器：缩略图 + 名称 + “大小 · 时间 · 位置”。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    entry: VaultEntry,
    selected: Boolean,
    showPath: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondary = buildString {
        append(if (entry.isFolder) "文件夹" else formatBytes(entry.sizeBytes))
        if (entry.durationMs != null) append(" · ${formatDuration(entry.durationMs)}")
        if (entry.resolution != null) append(" · ${entry.resolution}")
        append(" · ${formatDateTime(entry.modifiedAtMillis)}")
        if (showPath && !entry.isFolder) {
            val dir = entry.relativePath.trim('/').substringBeforeLast('/', "")
            if (dir.isNotEmpty()) append(" · $dir")
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(entry = entry, size = 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.favorite) {
            Icon(Icons.Filled.Favorite, contentDescription = "已收藏", tint = KindColors.document, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(
    entry: VaultEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Thumb(
            entry = entry,
            size = 120,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    else Modifier
                ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EntryList(
    entries: List<VaultEntry>,
    selection: Set<Long>,
    showPath: Boolean,
    asGrid: Boolean,
    onOpen: (VaultEntry) -> Unit,
    onToggleSelect: (VaultEntry) -> Unit,
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
) {
    if (asGrid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(items = entries, key = { it.id }) { entry ->
                FileGridCell(
                    entry = entry,
                    selected = entry.id in selection,
                    onClick = { if (selection.isEmpty()) onOpen(entry) else onToggleSelect(entry) },
                    onLongClick = { onToggleSelect(entry) },
                )
            }
        }
    } else {
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            items(items = entries, key = { it.id }) { entry ->
                FileRow(
                    entry = entry,
                    selected = entry.id in selection,
                    showPath = showPath,
                    onClick = { if (selection.isEmpty()) onOpen(entry) else onToggleSelect(entry) },
                    onLongClick = { onToggleSelect(entry) },
                )
            }
        }
    }
}

/** 选择模式的替代标题栏；动作以列表描述，页面负责派发。 */
data class SelectionMenuItem(val label: String, val action: SelectionAction)

enum class SelectionAction {
    MOVE, COPY, RENAME, TRASH, EXPORT_PUBLIC, EXPORT_TREE, MOVE_OUT, COMPRESS,
    /** 批量解压：只在选中项里含压缩包时出现 */
    EXTRACT,
    FAVORITE, RESTORE, DELETE_FOREVER, EMPTY_TRASH
}

@Composable
fun SelectionBar(
    count: Int,
    menuItems: List<SelectionMenuItem>,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAction: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "取消选择",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(8.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "已选 $count 项",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            "全选",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSelectAll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Box {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { menuOpen = true }
                    .padding(8.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menuItems.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            menuOpen = false
                            onAction(item.action)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            VaultButton(label = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun VaultButton(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val background = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        destructive -> MaterialTheme.colorScheme.error
        filled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        destructive -> MaterialTheme.colorScheme.onError
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
fun ImportProgressPanel(
    label: String,
    fraction: Float,
    detail: String,
    onCancel: () -> Unit,
    cancelLabel: String = "取消",
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text(cancelLabel, color = MaterialTheme.colorScheme.error, modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun BusyOverlay(visible: Boolean) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            // 忙的时候这一层必须把事件吃掉：否则底下的 FAB、列表行的长按菜单照样能点，
            // 解压进行中去点"移入回收站"会把正在读的源文件挪走
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text("处理中…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
