package com.qoder.pocketvault.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qoder.pocketvault.core.VaultRootMode
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.ui.LocalSnackbar
import com.qoder.pocketvault.ui.components.VaultButton
import com.qoder.pocketvault.ui.vm.SettingsViewModel

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { vm.reload() }
    LaunchedEffect(state.message) {
        state.message?.let { text ->
            val host = snackbar
            if (host != null) host.showSnackbar(text)
            else android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Section("隔离存储位置") {
            RootOption(
                title = "应用内部私有目录（推荐）",
                detail = "${state.internalPath}\nSELinux 沙箱保护，其他应用与 USB(MTP) 都看不到；容量受 /data 分区限制。",
                selected = state.rootMode == VaultRootMode.INTERNAL,
                enabled = state.isEmptyVault || state.rootMode == VaultRootMode.INTERNAL,
                onSelect = { vm.switchRoot(VaultRootMode.INTERNAL) },
            )
            RootOption(
                title = "外置存储沙箱目录",
                detail = "${state.externalPath}\nAndroid 11 起其他应用无法直接读取，容量更大；少数厂商文件管理器仍可能通过特权浏览到该路径。",
                selected = state.rootMode == VaultRootMode.EXTERNAL_SANDBOX,
                enabled = state.isEmptyVault || state.rootMode == VaultRootMode.EXTERNAL_SANDBOX,
                onSelect = { vm.switchRoot(VaultRootMode.EXTERNAL_SANDBOX) },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "当前：${state.currentPath}\n已用 ${formatBytes(state.usedBytes)} · 剩余 ${formatBytes(state.usableBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.isEmptyVault) {
                Text(
                    "切换位置仅在文件库为空时允许，请先导出内容或清空回收站与全部条目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Section("回收站") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("保留天数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                listOf(7, 15, 30, 90).forEach { days ->
                    VaultButton(
                        label = "$days",
                        filled = days == state.retentionDays,
                        onClick = { vm.setRetention(days) },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }

        Section("已授权的来源文件夹") {
            if (state.trees.isEmpty()) {
                Text("没有可复用的持久授权。导入文件夹时授予的读取权限会保留在这里，用于增量重新同步。", style = MaterialTheme.typography.bodySmall)
            }
            state.trees.forEach { permission ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        permission.uri.lastPathSegment ?: "文件夹",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    VaultButton("重新同步", filled = false) { vm.resyncTree(permission.uri) }
                    VaultButton("撤销", filled = false, modifier = Modifier.padding(start = 6.dp)) { vm.forgetTree(permission.uri) }
                }
            }
        }

        Section("索引与缓存") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultButton("重建索引（磁盘与数据库对账）") { vm.rescanIndex() }
                VaultButton("清空缩略图缓存（${formatBytes(state.thumbCacheBytes)}）", filled = false) { vm.clearThumbCache() }
            }
        }

        Section("这些内容为什么看不到") {
            Text(
                "· 导入是复制，原文件仍留在手机里的原位置，本应用不会删除它们。\n" +
                    "· 内容不写入 MediaStore，也不做媒体扫描，所以系统相册、音乐、文件管理器看不到；导出后才会公开可见。\n" +
                    "· 应用已关闭系统备份与换机迁移，避免私有目录被上传。\n" +
                    "· 卸载应用或使用“清除存储”会一并删除这些内容，重要资料请先导出。\n" +
                    "· 已 root 的设备或厂商系统应用不受沙箱限制，本应用不是对抗性保密工具。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun RootOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.selectable(selected = selected, onClick = onSelect) else Modifier
            )
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = if (enabled) onSelect else null, enabled = enabled)
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.4f),
            )
        }
    }
}
