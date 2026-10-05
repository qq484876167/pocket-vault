package com.qoder.pocketvault.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.qoder.pocketvault.data.db.VaultEntry

/**
 * 图片查看。两种模式都用最朴素的 `AsyncImage(model = File)`——
 * 条漫模式一直正常、翻页模式曾经整屏纯色，差别就出在翻页模式上我曾改成自定义 ImageRequest，
 * 所以这里统一回到经过验证的那条路径，放大只做位图缩放（与系统图库一致）。
 */
@Composable
fun ImagePagerViewer(
    entries: List<VaultEntry>,
    index: Int,
    rotation: Int,
    onIndexChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = index) { entries.size }
    val zoom = remember { PinchZoomState() }

    LaunchedEffect(index) {
        if (pagerState.currentPage != index && !pagerState.isScrollInProgress) pagerState.scrollToPage(index)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onIndexChanged(it) }
    }
    LaunchedEffect(pagerState.currentPage) { zoom.reset() }

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !zoom.zoomed,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) { page ->
        ZoomableImage(
            entry = entries[page],
            rotation = rotation,
            zoom = zoom,
            onTap = onTap,
            onOpenExternal = onOpenExternal,
        )
    }
}

/** 条漫式上下连续滚动：图片首尾相接，不叠加文件名。 */
@Composable
fun ImageStripViewer(
    entries: List<VaultEntry>,
    index: Int,
    onIndexChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = index)

    LaunchedEffect(index) {
        if (listState.firstVisibleItemIndex != index && !listState.isScrollInProgress) listState.scrollToItem(index)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onIndexChanged(it) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        items(items = entries, key = { it.id }) { entry ->
            StripImage(entry = entry, onTap = onTap, onOpenExternal = onOpenExternal)
        }
    }
}

@Composable
private fun StripImage(entry: VaultEntry, onTap: () -> Unit, onOpenExternal: () -> Unit) {
    val resolved by rememberResolvedFile(entry)
    var decodeError by remember(entry.id) { mutableStateOf<String?>(null) }
    val ratio = imageAspectRatio(entry)
    when (val state = resolved) {
        is ResolvedFile.Loading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) }

        is ResolvedFile.Failed -> ViewerFailure(entry, "读取失败：" + state.reason, onOpenExternal)

        is ResolvedFile.Ready -> if (decodeError != null) {
            ViewerFailure(entry, "解码失败：$decodeError", onOpenExternal)
        } else {
            AsyncImage(
                model = state.file,
                contentDescription = entry.name,
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                onError = { result -> decodeError = result.result.throwable.toString() },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clickable(onClick = onTap),
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    entry: VaultEntry,
    rotation: Int,
    zoom: PinchZoomState,
    onTap: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val resolved by rememberResolvedFile(entry)
    var decodeError by remember(entry.id) { mutableStateOf<String?>(null) }
    val transformable = rememberTransformableFor(zoom)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = resolved) {
            is ResolvedFile.Loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)

            is ResolvedFile.Failed -> ViewerFailure(entry, "读取失败：" + state.reason, onOpenExternal)

            is ResolvedFile.Ready -> if (decodeError != null) {
                ViewerFailure(entry, "解码失败：$decodeError", onOpenExternal)
            } else {
                AsyncImage(
                    model = state.file,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                    onError = { result -> decodeError = result.result.throwable.toString() },
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomTransform(zoom, rotation)
                        // 未放大时只接两指捏合，单指滑动留给翻页；放大后才接管拖动
                        .then(
                            if (zoom.zoomed) Modifier.transformable(state = transformable)
                            else Modifier.pinchToZoom(zoom)
                        )
                        .pointerInput(entry.id, rotation) {
                            detectTapGestures(onTap = { onTap() })
                        },
                )
            }
        }
    }
}

private fun imageAspectRatio(entry: VaultEntry): Float {
    val width = entry.width ?: 0
    val height = entry.height ?: 0
    return if (width > 0 && height > 0) {
        (width.toFloat() / height.toFloat()).coerceIn(0.15f, 6f)
    } else {
        0.7f
    }
}

@Composable
fun ViewerFailure(entry: VaultEntry, reason: String, onOpenExternal: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            entry.name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            reason,
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "用其他应用打开",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(onClick = onOpenExternal)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
fun UnsupportedViewer(entry: VaultEntry?, onOpenExternal: () -> Unit) {
    ViewerFailure(
        entry = entry ?: fallbackEntry(),
        reason = "这类内容没有应用内预览",
        onOpenExternal = onOpenExternal,
    )
}

private fun fallbackEntry(): VaultEntry = VaultEntry(
    id = -1,
    relativePath = "",
    name = "未知文件",
    parentId = -1,
    kind = com.qoder.pocketvault.core.FileKind.OTHER,
    mimeType = null,
    sizeBytes = 0,
    modifiedAtMillis = 0,
    importedAtMillis = 0,
    sourceSignature = null,
)

