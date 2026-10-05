package com.qoder.pocketvault.ui.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.qoder.pocketvault.appGraph
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.util.TextBookLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 私有目录里某个条目的解析结果——失败原因带到界面上，避免只能无限转圈。 */
sealed interface ResolvedFile {
    data class Ready(val file: File) : ResolvedFile
    data object Loading : ResolvedFile
    data class Failed(val reason: String) : ResolvedFile
}

/** 这个条目能否在应用内查看器打开（列表、网格、详情页点击的分流依据）。 */
fun viewerSupports(entry: VaultEntry): Boolean = when {
    entry.isFolder -> false
    entry.kind == FileKind.IMAGE || entry.kind == FileKind.VIDEO -> true
    entry.kind == FileKind.DOCUMENT || entry.kind == FileKind.OTHER -> {
        FileKind.extensionOf(entry.name) == "pdf" || TextBookLoader.isPlainText(entry.name)
    }

    else -> false
}

@Composable
fun rememberResolvedFile(entry: VaultEntry?): State<ResolvedFile> {
    val app = LocalContext.current.applicationContext
    val holder = remember { MutableStateFlow<ResolvedFile>(ResolvedFile.Loading) }
    LaunchedEffect(entry?.id, entry?.relativePath, entry?.state) {
        holder.value = ResolvedFile.Loading
        // exists() / length() 是磁盘调用，必须放到 IO 线程
        holder.value = withContext(Dispatchers.IO) { resolve(entry, app) }
    }
    return holder.collectAsState()
}

private fun resolve(entry: VaultEntry?, context: Context): ResolvedFile {
    if (entry == null) return ResolvedFile.Failed("项目已不存在")
    return runCatching { context.appGraph().repo.physicalFile(entry) }.fold(
        onSuccess = { file ->
            when {
                !file.exists() -> ResolvedFile.Failed("文件不在预期位置：${entry.relativePath}")
                file.length() <= 0L -> ResolvedFile.Failed("文件大小为 0，可能已损坏")
                else -> ResolvedFile.Ready(file)
            }
        },
        onFailure = {
            android.util.Log.w("ViewerFiles", "解析失败 ${entry.relativePath}", it)
            ResolvedFile.Failed("${it.javaClass.simpleName}: ${it.message}")
        },
    )
}
