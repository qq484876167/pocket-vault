package com.qoder.pocketvault.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qoder.pocketvault.core.ColorMatrices
import com.qoder.pocketvault.data.db.PdfBookmark

/**
 * 夜间反色：只在显示层套一张反色矩阵（RGB 取负再加 255，Alpha 原样）。
 *
 * 不重新渲染页面，所以开关是即时的，也不会多占一份位图缓存。
 * 默认关着——照片 / 彩色插图为主的 PDF 反着看很难看。
 */
val PdfInvertFilter: ColorFilter = ColorFilter.colorMatrix(ColorMatrix(ColorMatrices.INVERT))

/**
 * 缩略图的渲染宽度。
 *
 * **360 是 PdfDocument 渲染下限**（`targetWidthPx.coerceIn(360, 1600)`），写更小的数会被抬到 360，
 * 所以这里必须按 360 记账：一张 360×509 的 ARGB_8888 约 0.7 MB，一屏 20 来格就是十几 MB，
 * 会和正文大图共用同一份 LRU（键里带宽度，互不覆盖但会互相挤出去）。
 * 实际表现：关掉总览回到正文，当前页多半要重画一次——预取 ±1 页的逻辑会立刻补上，
 * 只是闪过一下白底。要真的省这份内存得给缩略图单独做一条渲染通道，这轮没做。
 */
private const val THUMB_WIDTH_PX = 360

/**
 * 页面总览：一屏网格把所有页缩略图铺开，点哪页就跳过去。
 *
 * 只有滑进视野的那些格子会真的渲染（LazyVerticalGrid 的项是懒加载的），
 * 所以几百页的文件打开总览也是逐格出现，而不是转圈转到天荒地老。
 * 缩略图与正文用的是同一套 PdfDocument 缓存，键里带宽度，互不挤占。
 */
@Composable
fun PdfThumbGrid(
    pageCount: Int,
    current: Int,
    render: suspend (Int, Int) -> Bitmap?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("共 $pageCount 页，点一页直接跳过去", color = Color.White, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭总览",
                    tint = Color.White,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(onClick = onDismiss),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(count = pageCount, key = { it }) { page ->
                    ThumbCell(
                        page = page,
                        current = current,
                        render = render,
                        onClick = { onPick(page) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbCell(
    page: Int,
    current: Int,
    render: suspend (Int, Int) -> Bitmap?,
    onClick: () -> Unit,
) {
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(page) { mutableStateOf<String?>(null) }
    LaunchedEffect(page) {
        val outcome = runCatching { render(page, THUMB_WIDTH_PX) }
        bitmap = outcome.getOrNull()
        // 渲染失败要说出来：以前画不出来就永远转圈，用户只会以为总览卡住了
        failure = bitmap?.let { null }
            ?: (outcome.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "这一页画不出来")
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(132.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (page == current) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "第 ${page + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

                failure != null -> Text(
                    "${page + 1} 页失败",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.padding(4.dp),
                )

                else -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("${page + 1}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 书签列表。加书签用顶栏的按钮（加在当前这一页），这里负责看、跳、删。
 *
 * 存的是页码而不是百分比——和小说书签存"章 + 段落"是同一个道理，
 * 换设备、换屏幕比例都不会漂走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfBookmarkSheet(
    marks: List<PdfBookmark>,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("书签（${marks.size} 个）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (marks.isEmpty()) {
                Text(
                    "还没有书签。翻到想看的那一页，点顶栏的书签图标就能加在这里。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(marks, key = { it.pageIndex }) { mark ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onJump(mark.pageIndex)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "第 ${mark.pageIndex + 1} 页",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (mark.pageIndex >= pageCount) {
                            Text("这份文件里已经没有这页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { onDelete(mark.pageIndex) }) { Text("删除") }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
