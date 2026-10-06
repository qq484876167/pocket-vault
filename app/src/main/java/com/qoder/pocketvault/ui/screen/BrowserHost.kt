package com.qoder.pocketvault.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qoder.pocketvault.core.folderDisplayName
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.ui.vm.ExtractTarget
import com.qoder.pocketvault.ui.LocalSnackbar
import com.qoder.pocketvault.ui.components.EntryList
import com.qoder.pocketvault.ui.components.FolderPickerSheet
import com.qoder.pocketvault.ui.components.SelectionAction
import com.qoder.pocketvault.ui.components.SelectionBar
import com.qoder.pocketvault.ui.components.SelectionMenuItem
import com.qoder.pocketvault.ui.components.BusyOverlay
import com.qoder.pocketvault.ui.components.CompressDialog
import com.qoder.pocketvault.ui.components.ConfirmDialog
import com.qoder.pocketvault.ui.components.TextInputDialog
import com.qoder.pocketvault.ui.vm.BrowseViewModel

private enum class SheetMode { NONE, MOVE, COPY }

/**
 * 浏览类页面共用的一套外壳：导入入口、多选操作条、移动/复制/重命名/压缩/导出对话框、
 * 以及 VM 消息到 Snackbar 的桥接。库页、目录页、搜索页、回收站只负责给出条目和打开方式。
 */
@Composable
fun BrowserHost(
    vm: BrowseViewModel,
    entries: List<VaultEntry>,
    showPath: Boolean,
    asGrid: Boolean,
    contentPadding: PaddingValues = PaddingValues(top = 8.dp, bottom = 108.dp),
    emptyTitle: String = "这里还空着",
    emptyHint: String = "点右下角的「导入」把文件或整个文件夹放进来；内容只保存在本应用的私有目录里。",
    header: @Composable (() -> Unit)? = null,
    selectionMenu: List<SelectionMenuItem> = emptyList(),
    onMenuAction: ((SelectionAction) -> Unit)? = null,
    showImportFab: Boolean = true,
    onOpen: (VaultEntry) -> Unit,
) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val choices by vm.folderChoices.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current

    var sheetMode by remember { mutableStateOf(SheetMode.NONE) }
    var renameTarget by remember { mutableStateOf<VaultEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showCompress by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var filePickerWantsMove by remember { mutableStateOf(false) }
    var pendingMoveFiles by remember { mutableStateOf<List<Uri>?>(null) }
    var pendingMoveTree by remember { mutableStateOf<Uri?>(null) }
    var treePurpose by remember { mutableStateOf(TreePurpose.IMPORT_COPY) }

    val context = LocalContext.current

    // 文档选择器要额外申请写授权，否则"移动导入"删不掉源文件
    val filePicker = rememberLauncherForActivityResult(OpenDocumentsReadWrite()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (filePickerWantsMove) pendingMoveFiles = uris else vm.importFiles(uris, deleteSource = false)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTO_PICK)) { uris ->
        // 系统媒体选择器只给一次性读授权，这个入口永远只能复制
        if (uris.isNotEmpty()) vm.importFiles(uris, deleteSource = false)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (treePurpose) {
            TreePurpose.IMPORT_COPY -> vm.importFolder(uri, deleteSource = false)
            TreePurpose.IMPORT_MOVE -> pendingMoveTree = uri
            TreePurpose.MOVE_OUT -> vm.moveSelectedToTree(uri)
        }
    }
    val exportTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.exportSelectedToTree(uri)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar?.showSnackbar(it) ?: android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    val defaultMenu = remember {
        listOf(
            SelectionMenuItem("移动到…", SelectionAction.MOVE),
            SelectionMenuItem("复制一份到…", SelectionAction.COPY),
            SelectionMenuItem("重命名", SelectionAction.RENAME),
            SelectionMenuItem("压缩为 zip", SelectionAction.COMPRESS),
            SelectionMenuItem("解压", SelectionAction.EXTRACT),
            SelectionMenuItem("导出到手机公共目录", SelectionAction.EXPORT_PUBLIC),
            SelectionMenuItem("导出到选择的文件夹…", SelectionAction.EXPORT_TREE),
            SelectionMenuItem("移动到手机里的文件夹…（移出本应用）", SelectionAction.MOVE_OUT),
            SelectionMenuItem("移入回收站", SelectionAction.TRASH),
        )
    }
    // 「解压」只在选中项里确实有压缩包时才有意义，否则不显示
    val hasArchiveSelected = entries.any { it.id in selection && it.kind == FileKind.ARCHIVE }
    val baseMenu = if (selectionMenu.isNotEmpty()) selectionMenu else defaultMenu
    val activeMenu = if (hasArchiveSelected) baseMenu
    else baseMenu.filterNot { it.action == SelectionAction.EXTRACT }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    menuItems = activeMenu,
                    onClose = { vm.clearSelection() },
                    onSelectAll = { vm.selectAll(entries.map { it.id }) },
                    onAction = { action ->
                        val external = onMenuAction
                        if (external != null) {
                            external(action)
                        } else when (action) {
                            SelectionAction.MOVE -> sheetMode = SheetMode.MOVE
                            SelectionAction.COPY -> sheetMode = SheetMode.COPY
                            SelectionAction.EXPORT_TREE -> exportTreePicker.launch(null)
                            SelectionAction.MOVE_OUT -> {
                                treePurpose = TreePurpose.MOVE_OUT
                                treePicker.launch(null)
                            }
                            SelectionAction.RENAME -> renameTarget = entries.firstOrNull { it.id == selection.first() }
                            SelectionAction.COMPRESS -> showCompress = true
                            SelectionAction.EXTRACT -> vm.extractArchives(
                                picked = entries.filter { it.id in selection },
                                target = ExtractTarget.NEW_FOLDER,
                            )
                            SelectionAction.TRASH -> vm.trashSelected()
                            SelectionAction.EXPORT_PUBLIC -> vm.exportSelectedToPublic()
                            SelectionAction.FAVORITE -> selection.forEach { vm.toggleFavorite(it, true) }
                            else -> Unit
                        }
                    },
                )
            }
            header?.invoke()
            Box(Modifier.weight(1f)) {
                if (entries.isEmpty()) {
                    com.qoder.pocketvault.ui.components.EmptyState(
                        icon = Icons.Filled.FolderOpen,
                        title = emptyTitle,
                        hint = emptyHint,
                    )
                } else {
                    EntryList(
                        entries = entries,
                        selection = selection,
                        showPath = showPath,
                        asGrid = asGrid,
                        contentPadding = contentPadding,
                        onOpen = onOpen,
                        onToggleSelect = { vm.toggleSelect(it.id) },
                    )
                }
            }
        }

        if (showImportFab) {
            ExtendedFloatingActionButton(
                onClick = { importMenu = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 12.dp),
            )
        }
        if (showImportFab) {
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 80.dp)) {
            DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                ImportMenuItem("选择文件（复制，保留原件）", Icons.Filled.UploadFile) {
                    importMenu = false
                    filePickerWantsMove = false
                    filePicker.launch(arrayOf("*/*"))
                }
                ImportMenuItem("选择文件（移动，导入后删除原件）", Icons.Filled.UploadFile) {
                    importMenu = false
                    filePickerWantsMove = true
                    filePicker.launch(arrayOf("*/*"))
                }
                ImportMenuItem("从相册选择（只能复制）", Icons.Filled.PhotoLibrary) {
                    importMenu = false
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                ImportMenuItem("整个文件夹（复制）", Icons.Filled.CreateNewFolder) {
                    importMenu = false
                    treePurpose = TreePurpose.IMPORT_COPY
                    treePicker.launch(null)
                }
                ImportMenuItem("整个文件夹（移动，删除原件）", Icons.Filled.CreateNewFolder) {
                    importMenu = false
                    treePurpose = TreePurpose.IMPORT_MOVE
                    treePicker.launch(null)
                }
                ImportMenuItem("新建文件夹", Icons.Filled.FolderOpen) {
                    importMenu = false
                    showNewFolder = true
                }
                }
            }
        }

        if (busy) BusyOverlay(true)
    }

    when (sheetMode) {
        SheetMode.MOVE -> FolderPickerSheet(
            choices = choices,
            excludePaths = setOf(currentDirOf(vm)),
            onDismiss = { sheetMode = SheetMode.NONE },
            onPick = { path ->
                sheetMode = SheetMode.NONE
                vm.moveToSelected(path)
            },
        )

        SheetMode.COPY -> FolderPickerSheet(
            choices = choices,
            excludePaths = emptySet(),
            onDismiss = { sheetMode = SheetMode.NONE },
            onPick = { path ->
                sheetMode = SheetMode.NONE
                vm.copyToSelected(path)
            },
        )

        SheetMode.NONE -> Unit
    }

    if (showNewFolder) {
        TextInputDialog(
            title = "在「${folderDisplayName(currentDirOf(vm))}」中新建文件夹",
            label = "文件夹名称",
            onDismiss = { showNewFolder = false },
            onConfirm = {
                showNewFolder = false
                vm.newFolder(it)
            },
        )
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = "重命名",
            label = "新名称",
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = {
                renameTarget = null
                vm.rename(target.id, it)
            },
        )
    }

    if (showCompress) {
        CompressDialog(
            defaultName = "压缩-${folderDisplayName(currentDirOf(vm))}",
            itemCount = entries.count { it.id in selection },
            totalBytes = entries.filter { it.id in selection }.sumOf { entry ->
                if (entry.isFolder) 0L else entry.sizeBytes
            },
            onDismiss = { showCompress = false },
            onConfirm = { name, password, level ->
                showCompress = false
                vm.compressSelected(name, password, level)
            },
        )
    }
    pendingMoveFiles?.let { uris ->
        ConfirmDialog(
            title = "移动 ${uris.size} 个文件",
            body = "复制成功并写入索引之后才删除这些源文件；删掉之后相册和文件管理器里就看不到它们了。" +
                "个别来源（云盘之类）不允许应用删除，那种情况会保留原件并在结果里说明。",
            confirmLabel = "移动并删除原件",
            onDismiss = { pendingMoveFiles = null },
            onConfirm = {
                pendingMoveFiles = null
                vm.importFiles(uris, deleteSource = true)
            },
        )
    }

    pendingMoveTree?.let { uri ->
        ConfirmDialog(
            title = "移动整个文件夹",
            body = "复制成功后删除里面的源文件，以及被搬空的子目录；你选择的那个顶层文件夹本身不会被删除。",
            confirmLabel = "移动并删除原件",
            onDismiss = { pendingMoveTree = null },
            onConfirm = {
                pendingMoveTree = null
                vm.importFolder(uri, deleteSource = true)
            },
        )
    }
}

private enum class TreePurpose { IMPORT_COPY, IMPORT_MOVE, MOVE_OUT }

/**
 * ACTION_OPEN_DOCUMENT 默认只给读权限，无法删除源文件；
 * 这里显式申请读写，"移动导入"才可能成功删除原件（用户仍需在弹窗里确认）。
 */
private class OpenDocumentsReadWrite : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK) return emptyList()
        val clip = intent?.clipData
        if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(intent?.data)
    }
}

@Composable
private fun ImportMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** BrowseViewModel 的 currentDir 是 StateFlow，这里为对话框标题取快照。 */
@Composable
private fun currentDirOf(vm: BrowseViewModel): String {
    val dir by remember(vm) { vm.currentDir }.collectAsStateWithLifecycle()
    return dir
}

private const val MAX_PHOTO_PICK = 200
