package com.qoder.pocketvault.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.qoder.pocketvault.appGraph
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.ui.components.VaultButton

/**
 * 首次进入的导入引导。三个入口都不需要存储权限：
 * 文档选择器（SAF）负责任意文件与整个文件夹，系统媒体选择器负责照片/视频。
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph()
    val progress by graph.importer.progress.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) graph.importer.importFiles(uris, "")
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(200)) { uris ->
        if (uris.isNotEmpty()) graph.importer.importFiles(uris, "")
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) graph.importer.importTree(uri, "", rememberFolder = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Icon(
            Icons.Filled.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(34.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("把内容收进只有本应用能看到的目录", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "导入不会移动或删除手机里的原文件，而是复制一份到本应用的私有目录。系统文件管理器、相册、音乐库都扫描不到这里，只有打开这个应用才能看到它们。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))) {
            Column(Modifier.padding(14.dp)) {
                Text("私有目录位置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    graph.rootPath(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text("该目录可用空间约 ${formatBytes(graph.paths.usableBytes())}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("现在导入一些内容", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.Start) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultButton("选择文件") { filePicker.launch(arrayOf("*/*")) }
                VaultButton("从相册选择", filled = false) {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultButton("导入整个文件夹") { treePicker.launch(null) }
                VaultButton("稍后再说", filled = false) { onFinished() }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "导入整个文件夹会保留原有层级结构，例如 Photos/2026/春节/IMG_0001.jpg 仍然是三层。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        if (progress.active) {
            Spacer(Modifier.height(16.dp))
            ImportProgressHost(
                progress = progress,
                onCancel = graph.importer::cancel,
                onDismiss = graph.importer::dismissProgress,
            )
        } else {
            Spacer(Modifier.height(16.dp))
            VaultButton("进入文件库") { onFinished() }
            progress.message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "提示：卸载应用会连同私有目录一起删除。需要长期保存的内容请主动导出到公共目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
        )
    }
}
