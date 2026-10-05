package com.qoder.pocketvault.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qoder.pocketvault.ui.LocalNavigator

/** 应用内媒体查看的总入口：图片 / 视频 / PDF，黑底沉浸式，单击显示或隐藏工具条。 */
@Composable
fun MediaViewerScreen(vm: ViewerViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val rotation by vm.rotation.collectAsStateWithLifecycle()
    val subject by vm.subject.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pdfPages by vm.pdfPageCount.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    var chrome by remember { mutableStateOf(true) }
    var showJump by remember { mutableStateOf(false) }

    val total = when (subject) {
        ViewerSubject.PDF -> pdfPages
        ViewerSubject.TEXT -> vm.book.value?.chapters?.size ?: 0
        else -> entries.size
    }
    val widthPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp.toPx()).toInt()
    }

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 阅读器自带顶栏（含返回），这里就不再叠一条
            if (chrome && subject != ViewerSubject.TEXT) {
                ViewerTopBar(
                    title = entries.getOrNull(index)?.name ?: "查看",
                    canToggleMode = vm.canToggleMode,
                    stripMode = mode == ViewerMode.STRIP,
                    showRotate = subject == ViewerSubject.IMAGE || subject == ViewerSubject.PDF,
                    onBack = { navigator?.goBack() },
                    onToggleMode = vm::toggleMode,
                    onRotate = vm::rotate,
                    onOpenExternal = { vm.openWithExternal(context) },
                )
                if (total > 1) {
                    LinearProgressIndicator(
                        progress = { ((index + 1f) / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                if (!ready) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                } else {
                    when (subject) {
                        ViewerSubject.IMAGE -> {
                            if (mode == ViewerMode.PAGER) {
                                ImagePagerViewer(
                                    entries = entries,
                                    index = index,
                                    rotation = rotation,
                                    onIndexChanged = vm::onIndexChanged,
                                    onTap = { chrome = !chrome },
                                    onOpenExternal = { vm.openWithExternal(context) },
                                )
                            } else {
                                ImageStripViewer(
                                    entries = entries,
                                    index = index,
                                    onIndexChanged = vm::onIndexChanged,
                                    onTap = { chrome = !chrome },
                                    onOpenExternal = { vm.openWithExternal(context) },
                                )
                            }
                        }

                        ViewerSubject.TEXT -> TextViewer(
                            vm = vm,
                            chrome = chrome,
                            onTap = { chrome = !chrome },
                            onBack = { navigator?.goBack() },
                        )

                        ViewerSubject.VIDEO -> VideoPagerViewer(
                            entries = entries,
                            index = index,
                            onIndexChanged = vm::onIndexChanged,
                            onTap = { chrome = !chrome },
                            onOpenExternal = { vm.openWithExternal(context) },
                        )

                        ViewerSubject.PDF -> PdfViewer(
                            index = index,
                            pageCount = pdfPages,
                            mode = mode,
                            rotation = rotation,
                            widthPx = widthPx,
                            onIndexChanged = vm::onIndexChanged,
                            onTap = { chrome = !chrome },
                            render = { page, px -> vm.renderPdfPage(page, px) },
                        )

                        ViewerSubject.UNSUPPORTED -> UnsupportedViewer(
                            entry = entries.getOrNull(index),
                            onOpenExternal = { vm.openWithExternal(context) },
                        )
                    }
                }
            }

            if (chrome && total > 1 && subject != ViewerSubject.TEXT) {
                ViewerBottomBar(
                    index = index,
                    total = total,
                    label = when (subject) {
                        ViewerSubject.PDF -> "页"
                        ViewerSubject.VIDEO -> "个"
                        else -> "张"
                    },
                    onJump = { showJump = true },
                )
            }
        }
    }

    if (showJump && total > 1) {
        JumpDialog(
            total = total,
            onDismiss = { showJump = false },
            onConfirm = { target ->
                showJump = false
                vm.onIndexChanged(target)
            },
        )
    }
}

@Composable
private fun ViewerTopBar(
    title: String,
    canToggleMode: Boolean,
    stripMode: Boolean,
    showRotate: Boolean,
    onBack: () -> Unit,
    onToggleMode: () -> Unit,
    onRotate: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "关闭",
            tint = Color.White,
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onBack)
                .padding(8.dp),
        )
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (canToggleMode) {
            Icon(
                imageVector = if (stripMode) Icons.Filled.ViewColumn else Icons.Filled.ViewStream,
                contentDescription = if (stripMode) "切换为左右翻页" else "切换为上下连续滚动",
                tint = Color.White,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onToggleMode)
                    .padding(8.dp),
            )
        }
        if (showRotate) {
            Icon(
                Icons.Filled.RotateRight,
                contentDescription = "旋转",
                tint = Color.White,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onRotate)
                    .padding(8.dp),
            )
        }
        Icon(
            Icons.Filled.OpenInNew,
            contentDescription = "用其他应用打开",
            tint = Color.White,
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onOpenExternal)
                .padding(8.dp),
        )
    }
}

@Composable
private fun ViewerBottomBar(index: Int, total: Int, label: String, onJump: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1} / $total $label",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "跳转",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clickable(onClick = onJump)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun JumpDialog(total: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳到第几${if (total > 0) "项" else "页"}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(6) },
                    label = { Text("1 - $total") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("输入序号可直接定位，避免几百项里一页页翻。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = text.toIntOrNull()?.minus(1)
                if (target != null && target in 0 until total) onConfirm(target)
            }) { Text("跳转") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
