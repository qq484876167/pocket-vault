package com.qoder.pocketvault.ui.viewer

import android.app.Activity
import android.media.MediaPlayer
import android.view.WindowManager
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qoder.pocketvault.core.formatDuration
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.ui.components.rememberEntryFile
import kotlinx.coroutines.delay

/** 视频：左右滑动切换同目录下的视频，每页一个独立播放器实例。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoPagerViewer(
    entries: List<VaultEntry>,
    index: Int,
    onIndexChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = index) { entries.size }

    LaunchedEffect(index) {
        if (pagerState.currentPage != index && !pagerState.isScrollInProgress) pagerState.scrollToPage(index)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onIndexChanged(it) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) { page ->
        val entry = entries[page]
        // key 保证换页时旧播放器被销毁重建，避免多条解码管线同时占用
        androidx.compose.runtime.key(entry.id) {
            VideoStage(
                entry = entry,
                isActive = page == pagerState.currentPage,
                onTap = onTap,
                onOpenExternal = onOpenExternal,
            )
        }
    }
}

@Composable
private fun VideoStage(
    entry: VaultEntry,
    isActive: Boolean,
    onTap: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    val file = rememberEntryFile(entry)
    var videoView by remember(entry.id) { mutableStateOf<VideoView?>(null) }
    var prepared by remember(entry.id) { mutableStateOf(false) }
    var playing by remember(entry.id) { mutableStateOf(false) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    var durationMs by remember(entry.id) { mutableLongStateOf(0L) }
    var positionMs by remember(entry.id) { mutableLongStateOf(0L) }
    var dragging by remember(entry.id) { mutableStateOf(false) }
    var scrub by remember(entry.id) { mutableFloatStateOf(0f) }

    DisposableEffect(entry.id) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            videoView?.let { view -> runCatching { view.stopPlayback() } }
            videoView = null
        }
    }

    LaunchedEffect(isActive, prepared) {
        val view = videoView ?: return@LaunchedEffect
        if (!isActive) {
            if (playing) {
                runCatching { view.pause() }
                playing = false
            }
        } else if (prepared && !playing) {
            runCatching { view.start() }.onSuccess {
                playing = true
                // 播完时 positionMs 被钉在结尾，而下面的 tick 是单调不减的：
                // 不重新对齐的话，整个回放过程进度条和时间都卡在结尾
                positionMs = view.currentPosition.toLong().coerceAtMost(durationMs)
            }
        }
    }

    LaunchedEffect(playing, dragging) {
        while (playing && !dragging) {
            videoView?.let { positionMs = it.currentPosition.toLong().coerceAtLeast(positionMs) }
            delay(300)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setOnPreparedListener { player ->
                        prepared = true
                        durationMs = player.duration.coerceAtLeast(1).toLong()
                        error = null
                    }
                    setOnCompletionListener {
                        playing = false
                        positionMs = durationMs
                    }
                    setOnErrorListener { _, what, extra ->
                        prepared = false
                        playing = false
                        error = "这台机器的解码器放不了这个文件（what=$what, extra=$extra）"
                        true
                    }
                    file?.absolutePath?.let { path ->
                        runCatching { setVideoPath(path) }.onFailure { error = "无法读取这个文件" }
                    } ?: run { error = "文件不存在或仍在回收站中" }
                }.also { videoView = it }
            },
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
                .clickable { onTap() },
        )

        if (!prepared && error == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
                color = Color.White,
                strokeWidth = 2.5.dp,
            )
        }

        error?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(entry.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(message, color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(12.dp))
                Text(
                    "改用其他应用打开",
                    color = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onOpenExternal)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        ) {
            Text(
                entry.name,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(enabled = prepared) {
                            videoView?.let { view ->
                                if (playing) {
                                    runCatching { view.pause() }
                                    playing = false
                                } else {
                                    runCatching { view.start() }
                                        .onSuccess { playing = true }
                                }
                            }
                        },
                )
                Spacer(Modifier.width(6.dp))
                Text(formatDuration(positionMs), color = Color.White, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = if (dragging) scrub else if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f,
                    onValueChange = { dragging = true; scrub = it },
                    onValueChangeFinished = {
                        val view = videoView
                        val target = (scrub * durationMs.toFloat()).toLong().coerceAtLeast(0L)
                        view?.seekTo(target.toInt())
                        positionMs = target
                        dragging = false
                    },
                    enabled = prepared && durationMs > 0,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Text(formatDuration(durationMs), color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
