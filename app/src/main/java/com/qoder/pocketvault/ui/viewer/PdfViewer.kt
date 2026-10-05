package com.qoder.pocketvault.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.qoder.pocketvault.util.PdfDocument

private val PdfBackdrop = Color(0xFF2A2A2A)
private const val FALLBACK_WIDTH = 800

/**
 * PDF 阅读。渲染策略保持简单：整页按 1.5 倍屏宽（封顶 1600px）画一次，缓存复用；
 * 翻页时预取相邻页，所以滑动到下一页基本是瞬间出图。
 * 放大走 graphicsLayer，跟系统图库一致，不再做按档位重新解码。
 */
@Composable
fun PdfViewer(
    index: Int,
    pageCount: Int,
    mode: ViewerMode,
    rotation: Int,
    widthPx: Int,
    onIndexChanged: (Int) -> Unit,
    onTap: () -> Unit,
    render: suspend (Int, Int) -> Bitmap?,
) {
    if (pageCount <= 0) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("这份文件没有可显示的页面", color = Color.White)
        }
        return
    }

    val targetWidth = (widthPx * 1.5f).toInt().coerceAtMost(PdfDocument.RENDER_WIDTH)

    if (mode == ViewerMode.PAGER) {
        val pagerState = rememberPagerState(initialPage = index.coerceIn(0, pageCount - 1)) { pageCount }
        val zoom = remember { PinchZoomState() }
        LaunchedEffect(index) {
            if (pagerState.currentPage != index && !pagerState.isScrollInProgress) pagerState.scrollToPage(index)
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { onIndexChanged(it) }
        }
        LaunchedEffect(pagerState.currentPage) { zoom.reset() }
        // 预取只留 ±1 页：ARGB_8888 下每页约 13.8 MB，再多就会把当前页挤出缓存
        LaunchedEffect(pagerState.currentPage, pageCount, targetWidth) {
            val current = pagerState.currentPage
            listOf(current + 1, current - 1).filter { it in 0 until pageCount }
                .forEach { runCatching { render(it, targetWidth) } }
        }
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoom.zoomed,
            modifier = Modifier
                .fillMaxSize()
                .background(PdfBackdrop),
        ) { page ->
            PdfPageImage(
                page = page,
                targetWidth = targetWidth,
                rotation = rotation,
                zoom = zoom,
                render = render,
                onTap = onTap,
                fillViewport = true,
            )
        }
    } else {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = index)
        LaunchedEffect(index) {
            if (listState.firstVisibleItemIndex != index && !listState.isScrollInProgress) {
                listState.scrollToItem(index)
            }
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { onIndexChanged(it) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(PdfBackdrop),
        ) {
            items(count = pageCount) { page ->
                PdfPageImage(
                    page = page,
                    targetWidth = targetWidth,
                    rotation = rotation,
                    zoom = remember(page) { PinchZoomState() },
                    render = render,
                    onTap = onTap,
                    fillViewport = false,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun PdfPageImage(
    page: Int,
    targetWidth: Int,
    rotation: Int,
    zoom: PinchZoomState,
    render: suspend (Int, Int) -> Bitmap?,
    onTap: () -> Unit,
    fillViewport: Boolean,
) {
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(page) { mutableStateOf<String?>(null) }
    val transformable = rememberTransformableFor(zoom)

    LaunchedEffect(page, targetWidth) {
        val first = runCatching { render(page, targetWidth) }
        val image = first.getOrNull() ?: runCatching { render(page, FALLBACK_WIDTH) }.getOrNull()
        if (image != null) {
            bitmap = image
            failure = null
        } else {
            failure = (first.exceptionOrNull() as? Exception)
                ?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "渲染返回空位图"
            android.util.Log.w("PdfViewer", "page $page render failed", first.exceptionOrNull())
        }
    }

    Box(
        modifier = if (fillViewport) {
            Modifier
                .fillMaxSize()
                .padding(6.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
        },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image == null && failure != null -> Text(
                "第 ${page + 1} 页 $failure",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )

            image == null -> Column(Modifier.height(200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            }

            else -> Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "第 ${page + 1} 页",
                contentScale = if (fillViewport) ContentScale.Fit else ContentScale.FillWidth,
                alignment = Alignment.Center,
                modifier = Modifier
                    .then(if (fillViewport) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .zoomTransform(zoom, rotation)
                    .then(
                        if (zoom.zoomed) Modifier.transformable(state = transformable)
                        else Modifier.pinchToZoom(zoom)
                    )
                    .pointerInput(page, rotation) {
                        detectTapGestures(onTap = { onTap() })
                    },
            )
        }
    }
}
