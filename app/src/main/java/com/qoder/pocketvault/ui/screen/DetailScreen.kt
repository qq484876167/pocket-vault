package com.qoder.pocketvault.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.core.formatDateTime
import com.qoder.pocketvault.core.formatDuration
import com.qoder.pocketvault.core.folderDisplayName
import com.qoder.pocketvault.ui.LocalNavigator
import com.qoder.pocketvault.ui.LocalSnackbar
import com.qoder.pocketvault.ui.components.ConfirmDialog
import com.qoder.pocketvault.ui.components.TextInputDialog
import com.qoder.pocketvault.ui.components.VaultButton
import com.qoder.pocketvault.ui.components.kindColor
import com.qoder.pocketvault.ui.vm.ArchiveUiState
import com.qoder.pocketvault.ui.vm.DetailViewModel
import com.qoder.pocketvault.ui.viewer.viewerSupports
import com.qoder.pocketvault.util.PreviewContent

@Composable
fun DetailScreen(vm: DetailViewModel) {
    val entry by vm.entry.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val archive by vm.archive.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    var showRename by remember { mutableStateOf(false) }
    var showMoveOut by remember { mutableStateOf(false) }

    val moveOutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.moveToTree(uri) { navigator?.goBack() }
    }

    LaunchedEffect(Unit) { vm.reload() }
    LaunchedEffect(message) {
        message?.let {
            snackbar?.showSnackbar(it) ?: android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    val current = entry
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.clickable { navigator?.goBack() }.padding(10.dp),
            )
            Text(
                current?.name ?: "详情",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            current?.let { item ->
                Icon(
                    if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (item.favorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.clickable { vm.setFavorite(!item.favorite) }.padding(10.dp),
                )
            }
        }

        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该项目已不存在，可能已被删除。")
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            PreviewBlock(current.kind, preview)

            if (current.kind == FileKind.ARCHIVE) {
                Spacer(Modifier.height(12.dp))
                ArchiveCard(
                    state = archive,
                    password = archive.password,
                    onPasswordChange = vm::setPassword,
                    targetLabel = folderDisplayName(current.relativePath.substringBeforeLast('/', "")),
                    picker = vm.folderTargets.ports(),
                    onList = vm::submitPassword,
                    onPlan = { target, specific -> vm.planExtract(target, specific) {} },
                    onExtract = { target, specific, policy, merge -> vm.extract(target, specific, policy, merge) },
                    onExtractOne = vm::extractEntry,
                    onPreviewOne = { item, sink -> vm.previewEntry(item, sink) },
                    onOpenDestination = { vm.openDestination { id -> navigator?.openFolder(id) } },
                    onCleanup = vm::cleanupPartialExtraction,
                    onOpenExternal = { vm.openWith(context) },
                )
            }

            Spacer(Modifier.height(14.dp))
            MetaCard(current)
            Spacer(Modifier.height(14.dp))

            val inAppViewable = viewerSupports(current)
            if (inAppViewable) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VaultButton(
                        when {
                            current.kind == FileKind.VIDEO -> "应用内播放"
                            current.kind == FileKind.IMAGE -> "应用内查看"
                            else -> "进入阅读"
                        },
                    ) { navigator?.openViewer(current.id) }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultButton("用其他应用打开", filled = false) { vm.openWith(context) }
                VaultButton("分享", filled = false) { vm.share(context) }
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultButton("导出到公共目录", filled = false) { vm.exportToPublic() }
                VaultButton("重命名", filled = false) { showRename = true }
                VaultButton("移入回收站", filled = false, destructive = true) {
                    vm.trash { navigator?.goBack() }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultButton("移动到手机里的文件夹…（移出本应用）", filled = false) { showMoveOut = true }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (busy) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }

    current?.let { safe ->
        if (showMoveOut) {
            ConfirmDialog(
                title = "移出本应用",
                body = "把「${safe.name}」复制到你选择的文件夹里；复制完成后核对字节数，一致才会从本应用删除。" +
                    "核对不通过时内容会留在文件库里，不会出现两边都没有的情况。",
                confirmLabel = "选择目标文件夹",
                onDismiss = { showMoveOut = false },
                onConfirm = {
                    showMoveOut = false
                    moveOutPicker.launch(null)
                },
            )
        }

        if (showRename) {
            TextInputDialog(
                title = "重命名",
                label = "新名称",
                initial = safe.name,
                onDismiss = { showRename = false },
                onConfirm = {
                    showRename = false
                    vm.rename(it)
                },
            )
        }
    }
}

@Composable
private fun PreviewBlock(kind: FileKind, preview: PreviewContent?) {
    when (preview) {
        null -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }

        is PreviewContent.Image -> AsyncImage(
            model = preview.file,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        is PreviewContent.Video -> Column {
            preview.thumb?.let { thumb ->
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "视频与音频交给系统播放器处理，文件仍留在私有目录，不会被相册或音乐库收录。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is PreviewContent.Text -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (preview.text.isEmpty()) {
                    Text("文件过大或不是可显示的文本内容。", style = MaterialTheme.typography.bodySmall)
                } else {
                    SelectionContainer {
                        Text(preview.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (preview.truncated) {
                        Spacer(Modifier.height(6.dp))
                        Text("（仅显示开头部分）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        is PreviewContent.Pdf -> Column {
            preview.pages.forEach { page ->
                AsyncImage(
                    model = page,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            if (preview.truncated) {
                Text(
                    "已渲染前 ${preview.pages.size} 页（共 ${preview.totalPages} 页），完整阅读请用其他应用打开。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        PreviewContent.None -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (kind == FileKind.ARCHIVE) 0.dp else 140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (kind == FileKind.ARCHIVE) androidx.compose.ui.graphics.Color.Transparent else kindColor(kind).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (kind != FileKind.ARCHIVE) {
                Text("这类文件没有内置预览，可用其他应用打开。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MetaCard(entry: com.qoder.pocketvault.data.db.VaultEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(14.dp)) {
            Line("类型", entry.kind.label)
            Line("大小", formatBytes(entry.sizeBytes))
            Line("修改时间", formatDateTime(entry.modifiedAtMillis))
            Line("导入时间", formatDateTime(entry.importedAtMillis))
            entry.resolution?.let { Line("尺寸", it) }
            entry.durationMs?.let { Line("时长", formatDuration(it)) }
            Line("库内路径", entry.relativePath)
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(86.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
