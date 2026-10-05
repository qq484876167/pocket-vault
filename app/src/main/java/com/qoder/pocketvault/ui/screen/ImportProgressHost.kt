package com.qoder.pocketvault.ui.screen

import androidx.compose.runtime.Composable
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.data.ImportProgress
import com.qoder.pocketvault.ui.components.ImportProgressPanel

/** 导入进度：活动时显示进度与取消，结束后只显示结果摘要并允许关闭。 */
@Composable
fun ImportProgressHost(progress: ImportProgress, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (!progress.active && progress.message == null) return
    val detail = buildString {
        if (progress.totalItems > 0) {
            append("已完成 ${progress.doneItems}/${progress.totalItems} 项")
            if (progress.skipped > 0) append("，跳过 ${progress.skipped} 项")
            if (progress.failed > 0) append("，失败 ${progress.failed} 项")
        }
        if (progress.totalBytes > 0) append(" · ${formatBytes(progress.doneBytes)} / ${formatBytes(progress.totalBytes)}")
        else if (progress.doneBytes > 0) append(" · 已写入 ${formatBytes(progress.doneBytes)}")
        if (progress.currentName.isNotBlank()) append("\n当前：${progress.currentName}")
        progress.message?.let { append("\n$it") }
    }
    if (progress.active) {
        ImportProgressPanel(
            label = progress.label.ifBlank { "正在导入" },
            fraction = progress.fraction,
            detail = detail,
            onCancel = onCancel,
        )
    } else {
        ImportProgressPanel(
            label = "导入结果",
            fraction = 1f,
            detail = detail,
            onCancel = onDismiss,
            cancelLabel = "知道了",
        )
    }
}
