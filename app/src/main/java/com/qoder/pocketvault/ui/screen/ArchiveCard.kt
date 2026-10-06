package com.qoder.pocketvault.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.data.ArchiveCapability
import com.qoder.pocketvault.data.ArchiveItem
import com.qoder.pocketvault.data.ConflictPolicy
import com.qoder.pocketvault.data.ExtractPlan
import com.qoder.pocketvault.data.ExtractSummary
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.ui.LocalNavigator
import com.qoder.pocketvault.ui.components.FilterChip
import com.qoder.pocketvault.ui.components.VaultButton
import com.qoder.pocketvault.ui.vm.ArchiveUiState
import com.qoder.pocketvault.ui.vm.BrowseViewModel
import com.qoder.pocketvault.ui.vm.EntryPreview
import com.qoder.pocketvault.ui.vm.ExtractTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 200

/**
 * 压缩包卡片。三件事：能力说清楚（不支持就给原因和出口）、选项摆在明面上
 * （落点 / 冲突策略 / 空间核对）、包内条目可以单独提取与预览。
 */
@Composable
fun ArchiveCard(
    state: ArchiveUiState,
    password: String,
    onPasswordChange: (String) -> Unit,
    targetLabel: String,
    folders: List<Pair<String, String>>,
    onList: (String) -> Unit,
    onPlan: (ExtractTarget, String?) -> Unit,
    onExtract: (ExtractTarget, String?, ConflictPolicy, Boolean) -> Unit,
    onExtractOne: (ArchiveItem, String) -> Unit,
    onPreviewOne: (ArchiveItem, String, (EntryPreview) -> Unit) -> Unit,
    onOpenDestination: () -> Unit,
    onCleanup: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    var target by remember { mutableStateOf(ExtractTarget.NEW_FOLDER) }
    var specific by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(ConflictPolicy.RENAME) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var shown by remember { mutableIntStateOf(PAGE_SIZE) }
    var menuFor by remember { mutableStateOf<ArchiveItem?>(null) }
    var preview by remember { mutableStateOf<EntryPreview?>(null) }
    var folderConflictAsked by remember { mutableStateOf(false) }

    val plan = state.plan
    val unsupported = state.capability == ArchiveCapability.UNSUPPORTED
    val askPassword = state.capability == ArchiveCapability.PASSWORD_REQUIRED ||
        state.needsPassword ||
        (state.error != null && state.items.isEmpty())

    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("压缩包内容", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))

        if (unsupported) {
            // 以前这里是一张空白卡片，什么提示都没有，用户只会以为应用坏了
            Text(
                state.unsupportedReason ?: "这个格式暂不支持在应用内打开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            VaultButton("用其他应用打开", filled = false, onClick = onOpenExternal)
            return@Column
        }

        if (askPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("压缩包密码（无密码留空）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            VaultButton("读取列表", onClick = { onList(password) })
        }

        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (state.items.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("解压到", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip("同名新文件夹（推荐）", target == ExtractTarget.NEW_FOLDER) {
                    target = ExtractTarget.NEW_FOLDER
                    plan?.let { if (it.suggestedFolder.isNotEmpty()) onPlan(target, null) }
                }
                FilterChip("当前文件夹（$targetLabel）", target == ExtractTarget.CURRENT_FOLDER) {
                    target = ExtractTarget.CURRENT_FOLDER
                    plan?.let { onPlan(target, null) }
                }
                FilterChip("指定文件夹…", target == ExtractTarget.SPECIFIC) { target = ExtractTarget.SPECIFIC }
            }
            if (target == ExtractTarget.SPECIFIC) {
                Spacer(Modifier.height(4.dp))
                folders.forEach { (path, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                specific = path
                                onPlan(ExtractTarget.SPECIFIC, path.ifBlank { null })
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (path == specific) Text("✓", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("遇到同名文件", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("保留两者（改名）", policy == ConflictPolicy.RENAME) { policy = ConflictPolicy.RENAME }
                FilterChip("覆盖", policy == ConflictPolicy.OVERWRITE) { policy = ConflictPolicy.OVERWRITE }
                FilterChip("跳过", policy == ConflictPolicy.SKIP) { policy = ConflictPolicy.SKIP }
            }
            Text("选一次，本次解压的全部条目都按这个来。", style = MaterialTheme.typography.bodySmall)
            if (policy == ConflictPolicy.OVERWRITE) {
                Text(
                    "「覆盖」不会抹掉原件：被替换的那份进回收站，随时能还原。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            plan?.let { value ->
                Spacer(Modifier.height(8.dp))
                Text(planLine(value), style = MaterialTheme.typography.bodySmall)
                if (value.requiredBytes >= 0 && value.availableBytes > 0) {
                    Text(
                        "需要约 ${formatBytes(value.requiredBytes)}，可用 ${formatBytes(value.availableBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (value.enoughSpace) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val blocked = plan?.let { !it.enoughSpace || it.needsPassword } == true
                VaultButton(
                    "解压",
                    enabled = !blocked,
                ) {
                    val dest = if (target == ExtractTarget.SPECIFIC) specific.ifBlank { null } else null
                    if (target == ExtractTarget.NEW_FOLDER && plan?.targetFolderExists == true && !folderConflictAsked) {
                        folderConflictAsked = true
                    } else {
                        onExtract(target, dest, policy, false)
                    }
                }
                if (plan == null) {
                    VaultButton("先核对大小与冲突", filled = false) {
                        onPlan(target, if (target == ExtractTarget.SPECIFIC) specific.ifBlank { null } else null)
                    }
                }
            }
            if (plan != null && !plan.enoughSpace) {
                Text("空间不够，先清一清或换个位置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        state.progress?.let { (written, total) ->
            Spacer(Modifier.height(8.dp))
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (written.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "已写出 ${formatBytes(written)}" + if (total > 0) " / ${formatBytes(total)}" else "（总大小未知）",
                style = MaterialTheme.typography.bodySmall,
            )
            // 解压目前挂在页面的协程作用域上，没有后台服务承载，得说清楚
            Text("请保持应用在前台：切走或锁屏可能中断解压。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }

        state.lastSummary?.let { summary ->
            Spacer(Modifier.height(8.dp))
            Text(summaryLine(summary), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultButton("进入该文件夹", filled = false, onClick = onOpenDestination)
                if (summary.partial) {
                    VaultButton("清理本次半成品", filled = false, destructive = true, onClick = onCleanup)
                }
            }
            summary.error?.let {
                Text("中途失败：$it（已解压的先留在库里，上面可一键清理）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        if (state.items.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("包内 ${state.items.size} 项", style = MaterialTheme.typography.labelLarge)
            ArchiveListing.visible(state.items, collapsed, shown).forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (item.isDirectory) {
                                collapsed = if (item.displayName in collapsed) collapsed - item.displayName
                                else collapsed + item.displayName
                            } else {
                                menuFor = item
                            }
                        }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (item.isDirectory) {
                            val mark = if (item.displayName in collapsed) "▸" else "▾"
                            "$mark ${item.displayName}/"
                        } else {
                            "     ${item.displayName}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            item.isDirectory -> ""
                            item.sizeBytes < 0 -> "—"
                            else -> formatBytes(item.sizeBytes)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (shown < state.items.size) {
                VaultButton("再显示 ${state.items.size - shown} 项", filled = false) { shown += PAGE_SIZE }
            }
        } else if (!askPassword && state.error == null && !state.loading) {
            Text("这个包里没有可显示的条目。", style = MaterialTheme.typography.bodySmall)
        }
    }

    menuFor?.let { item ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(item.displayName) },
            text = {
                Text(
                    buildString {
                        if (item.sizeBytes >= 0) append(formatBytes(item.sizeBytes))
                        if (!item.regularFile) append("\n这是链接类条目，为了安全不会落盘。")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        menuFor = null
                        onExtractOne(item, password)
                    },
                    enabled = item.regularFile,
                ) { Text("提取到 $targetLabel") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val chosen = item
                            menuFor = null
                            onPreviewOne(chosen, password) { preview = it }
                        },
                        enabled = item.regularFile,
                    ) { Text("预览") }
                    TextButton(onClick = { menuFor = null }) { Text("取消") }
                }
            },
        )
    }

    when (val body = preview) {
        null -> Unit
        is EntryPreview.Image -> EntryImageDialog(body.file.path) { preview = null }
        is EntryPreview.Text -> AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("预览") },
            text = { Text(body.body, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } },
        )

        is EntryPreview.NeedExtract -> AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("这一类内容不能直接预览") },
            text = { Text("先「提取」，再在列表里打开它。") },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("知道") } },
        )

        is EntryPreview.Error -> AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("预览失败") },
            text = { Text(body.message) },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("知道") } },
        )
    }

    if (folderConflictAsked && plan?.suggestedFolder != null) {
        AlertDialog(
            onDismissRequest = { folderConflictAsked = false },
            title = { Text("目标位置已有同名项目") },
            text = {
                val renameTo = plan.suggestedFolder.substringAfterLast('/')
                Text(
                    "合并进去：该文件夹里已有同名 ${plan.conflictCount} 处，" +
                        "会按上面「遇到同名文件」的选择处理。\n" +
                        "改名为「$renameTo」：那是一个新建的空文件夹，解压不会产生任何同名冲突。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        folderConflictAsked = false
                        onExtract(target, if (target == ExtractTarget.SPECIFIC) specific.ifBlank { null } else null, policy, true)
                    },
                ) { Text("合并") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            folderConflictAsked = false
                            onExtract(target, null, policy, false)
                        },
                    ) { Text("改名") }
                    TextButton(onClick = { folderConflictAsked = false }) { Text("取消") }
                }
            },
        )
    }
}

private fun planLine(plan: ExtractPlan): String = buildString {
    append("${plan.fileCount} 个文件")
    if (plan.totalBytes >= 0) append(" · 约 ${formatBytes(plan.totalBytes)}")
    if (plan.conflictCount > 0) append(" · 落点里已有同名 ${plan.conflictCount} 处")
    if (plan.skippedLinkCount > 0) append(" · 链接 ${plan.skippedLinkCount} 项不会落盘")
}

private fun summaryLine(summary: ExtractSummary): String = buildString {
    append("已解压 ${summary.written} 个文件到 ${summary.destRelativePath.ifEmpty { "根目录" }}")
    if (summary.renamed > 0) append("；改名 ${summary.renamed} 项")
    if (summary.overwritten > 0) append("；覆盖 ${summary.overwritten} 项（原件在回收站）")
    if (summary.skipped > 0) append("；跳过 ${summary.skipped} 项")
    if (summary.skippedLinks > 0) append("；链接 ${summary.skippedLinks} 项未落盘")
}

/**
 * 点压缩包直接给操作表（自带文件管理器就是这个顺序），不再要求先进详情页。
 * "解压到…"只列库内已有文件夹；想去库外，先解压再用列表里的"移出本应用"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveActionSheet(
    fileName: String,
    folders: List<String>,
    onExtract: (ExtractTarget, String?) -> Unit,
    onOpenContents: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(
                fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            SheetRow("解压到同名新文件夹", "推荐：不会把一堆文件倒在当前目录") {
                onExtract(ExtractTarget.NEW_FOLDER, null)
                onDismiss()
            }
            SheetRow("解压到当前文件夹", "包内条目直接落在压缩包所在目录") {
                onExtract(ExtractTarget.CURRENT_FOLDER, null)
                onDismiss()
            }
            SheetRow("解压到…（选库内文件夹）", "选一个已有的文件夹作为目标") { picking = true }
            SheetRow("查看压缩包内容", "密码、冲突策略、单条提取与预览都在这里") {
                onOpenContents()
                onDismiss()
            }
            Spacer(Modifier.height(14.dp))
        }
    }
    if (picking) {
        var chosen by remember { mutableStateOf(folders.firstOrNull().orEmpty()) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("解压到哪个文件夹") },
            text = {
                Column {
                    Text("只列库内已有文件夹；想放到手机里的其他位置，先解压再用「移出本应用」。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    folders.forEach { path ->
                        val label = if (path.isEmpty()) "文件库根目录" else path.replace("/", " ›")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { chosen = path }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (path == chosen) Text("✓")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        picking = false
                        onExtract(ExtractTarget.SPECIFIC, VaultPaths.normalizeRelative(chosen))
                        onDismiss()
                    },
                ) { Text("解压到这里") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("取消") } },
        )
    }
}

/** 单击压缩包的统一入口：文件库、目录、搜索结果三处共用同一套操作菜单。 */
@Composable
fun ArchiveOpenSheet(vm: BrowseViewModel, entry: VaultEntry?, onDismiss: () -> Unit) {
    if (entry == null) return
    val folders by vm.folderChoices.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    ArchiveActionSheet(
        fileName = entry.name,
        folders = folders,
        onExtract = { target, specific -> vm.extractArchives(listOf(entry), target, specific) },
        onOpenContents = { navigator?.openDetail(entry.id) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SheetRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

/** 目录折叠 + 分页，不再硬截断成 80 条。 */
private object ArchiveListing {

    fun visible(items: List<ArchiveItem>, collapsed: Set<String>, limit: Int): List<ArchiveItem> {
        val out = ArrayList<ArchiveItem>(minOf(limit, items.size))
        for (item in items) {
            if (out.size >= limit) break
            val hidden = collapsed.any { prefix -> item.displayName != prefix && item.displayName.startsWith("$prefix/") }
            if (!hidden) out += item
        }
        return out
    }
}

@Composable
private fun EntryImageDialog(path: String, onDismiss: () -> Unit) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预览") },
        text = {
            val image = bitmap
            if (image == null) Text("正在解码…")
            else Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
