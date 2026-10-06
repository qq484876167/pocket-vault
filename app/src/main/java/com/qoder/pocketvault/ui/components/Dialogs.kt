package com.qoder.pocketvault.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.data.ArchiveCompression
import com.qoder.pocketvault.data.FolderRow
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

/** 通用文本输入对话框：新建文件夹、重命名都用它。 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "确定",
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = if (singleLine) it.replace("\n", "") else it },
                    label = { Text(label) },
                    singleLine = singleLine,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "名称中的 / \\ 等字符会自动替换，避免破坏目录结构。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 压缩对话框：包名 + 压缩级别 + 可选密码（AES-256），并先把"要打包多少东西"显示出来。 */
@Composable
fun CompressDialog(
    defaultName: String,
    itemCount: Int,
    totalBytes: Long,
    onDismiss: () -> Unit,
    onConfirm: (name: String, password: String?, level: ArchiveCompression) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var password by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(ArchiveCompression.NORMAL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩为 zip") },
        text = {
            Column {
                Text(
                    "将打包 $itemCount 项 · 合计 ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("压缩包名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("压缩级别", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveCompression.values().forEach { option ->
                        FilterChip(option.label, option == level) { level = option }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（留空则不加密）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (level == ArchiveCompression.STORE) "「存储」只打包不压缩，加密时可正常读取。"
                    else "加密只保护压缩包本身，文件库里的原始条目不会被加密。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), password.ifBlank { null }, level) }) { Text("压缩") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 破坏性动作的二次确认（移动导入、移出本应用都走这里）。 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "确认",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 目标目录选择器需要的读写能力，由各自的 ViewModel 提供；组件本身不碰仓库。 */
data class FolderPickerPorts(
    /** 列出某个目录的直接子目录；返回 null 表示这个目录已经不存在了。 */
    val loadChildren: suspend (String) -> List<FolderRow>?,
    /** 在 parent 下新建目录，返回新目录的路径；失败返回 null。 */
    val createFolder: suspend (String, String) -> String?,
    /** 打开时可见的历史目标目录（调用方按存在性过滤过），新在前。 */
    val initialHistory: List<String>,
    /** 历史被就地改动（点到失效条目、清空）时写回持久化。 */
    val onHistoryChange: (List<String>) -> Unit,
)

/**
 * 移动 / 复制 / 解压的目标目录选择器：像系统文件管理器那样**逐层进入**。
 *
 * 一次只读当前这一层（含一次聚合查询拿到的子项数），不再把全库路径平铺出来。
 * [disabled] 是"不能选的原因表"：移动文件夹时它自己与所有后代都进不去，
 * 但复制与解压不受这个限制，所以由调用方决定传什么，组件不写死。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    ports: FolderPickerPorts,
    confirmLabel: String,
    disabled: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<FolderRow>?>(null) }
    var history by remember { mutableStateOf(ports.initialHistory) }
    var hint by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun jump(path: String) {
        hint = null
        // 先清空再读：不然新一层加载完之前显示的还是上一层的目录
        rows = null
        current = path
    }

    LaunchedEffect(current) {
        val loaded = ports.loadChildren(current)
        if (loaded == null) {
            // 这一层在本次会话里被删掉了（历史条目最常见的情况）：摘掉、说明原因、退回上一层
            history = history.filterNot { it == current }
            ports.onHistoryChange(history)
            hint = "该文件夹已不存在，已退回上一层"
            rows = null
            current = current.substringBeforeLast('/', "")
        } else {
            rows = loaded
        }
    }

    // 返回键先退一层，退到根目录才关Sheet——和系统文件管理器的移动界面一致
    BackHandler(enabled = current.isNotEmpty()) { jump(current.substringBeforeLast('/', "")) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("选择目标位置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Breadcrumb(current) { jump(it) }

            if (history.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("历史路径", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        history = emptyList()
                        ports.onHistoryChange(history)
                        hint = null
                    }) { Text("清空") }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.forEach { path ->
                        FilterChip(path.ifEmpty { "文件库" }.replace("/", " › "), false) { jump(path) }
                    }
                }
            }

            hint?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))

            val level = rows ?: emptyList()
            val pending = rows == null
            LazyColumn(modifier = Modifier.height(300.dp)) {
                items(level, key = { it.relativePath }) { row ->
                    val reason = disabled[row.relativePath]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (reason == null) 1f else 0.45f)
                            .then(
                                if (reason == null) Modifier.clickable { jump(row.relativePath) } else Modifier,
                            )
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            reason?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text("${row.childCount} 项", style = MaterialTheme.typography.bodySmall)
                        if (reason == null) Text("›", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (pending) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                } else if (level.isEmpty()) {
                    item {
                        Text(
                            "这一层还没有子文件夹，可以在下面新建一个。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            if (creating) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("新文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { creating = false }) { Text("取消") }
                    TextButton(
                        onClick = {
                            val name = draft.trim()
                            if (name.isEmpty()) return@TextButton
                            scope.launch {
                                val created = ports.createFolder(current, name)
                                if (created != null) {
                                    draft = ""
                                    creating = false
                                    jump(created)   // 建完直接进入，省得用户退出整个移动流程
                                } else {
                                    hint = "没能创建文件夹"
                                }
                            }
                        },
                    ) { Text("创建") }
                }
                Spacer(Modifier.height(6.dp))
            } else {
                TextButton(onClick = { creating = true }) { Text("在此新建文件夹") }
            }

            val blockedReason = disabled[current]
            blockedReason?.let {
                Text(
                    "当前这一层不能选：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultButton("取消", filled = false, onClick = onDismiss)
                VaultButton(
                    confirmLabel,
                    enabled = blockedReason == null && !pending,
                    onClick = { onPick(current) },
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** 面包屑：每一段都能点回该层，当前这段不可点。 */
@Composable
private fun Breadcrumb(current: String, onJump: (String) -> Unit) {
    val segments = current.split('/').filter { it.isNotEmpty() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val atRoot = segments.isEmpty()
        Text(
            "文件库",
            style = MaterialTheme.typography.bodyMedium,
            color = if (atRoot) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.primary,
            modifier = if (atRoot) Modifier else Modifier.clickable { onJump("") },
        )
        segments.forEachIndexed { index, segment ->
            Text(" › ", style = MaterialTheme.typography.bodyMedium)
            val last = index == segments.lastIndex
            val path = segments.take(index + 1).joinToString("/")
            Text(
                segment,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                color = if (last) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.primary,
                modifier = if (last) Modifier else Modifier.clickable { onJump(path) },
            )
        }
    }
}
