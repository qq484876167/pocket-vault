package com.qoder.pocketvault.ui.components

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.data.ArchiveCompression
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

/** 移动 / 复制 / 解压的目标目录选择器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    choices: List<String>,
    excludePaths: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val options = choices.filterNot { it in excludePaths }
    val tint = MaterialTheme.colorScheme.primary
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("选择目标位置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (options.isEmpty()) {
                Text("文件库里还没有其他文件夹，可先新建一个。", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(modifier = Modifier.height(360.dp)) {
                items(options) { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(path) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        Text(path.ifEmpty { "文件库根目录" }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
