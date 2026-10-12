package com.qoder.pocketvault.ui.viewer

import android.app.Activity
import android.content.Context
import android.media.MediaPlayer
import android.view.WindowManager
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qoder.pocketvault.core.PlaybackRules
import com.qoder.pocketvault.core.formatDuration
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.ui.components.rememberEntryFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 单击与双击要分开：等这么久而没有第二下，才把"显示/隐藏工具条"放出去。 */
private const val TAP_HOLD_MS = 260L

/** 双击左右半屏的快退 / 快进步长。 */
private const val SKIP_MS = 10_000L

/** seek 之后这段时间内直接采信解码器的当前位置，不再"进度只许前进"，否则倒退会被弹回去。 */
private const val SEEK_WINDOW_MS = 1_200L

/** 进度落盘的最小间隔；暂停、换页、退出这些时刻另外会立刻写一次。 */
private const val SAVE_EVERY_MS = 10_000L

/**
 * 单击、双击分区：左右两侧快退 / 快进 10 秒，中间播放暂停，其余地方显示或隐藏工具条。
 * 单击要晚 [TAP_HOLD_MS] 才生效，不然双击之前工具条会闪一下。
 */
private fun Modifier.tapZones(
    scope: CoroutineScope,
    key: Any?,
    onSingle: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: () -> Unit,
): Modifier = pointerInput(key) {
    // detectTapGestures 的回调不是协程体，PointerInputScope 也不是 CoroutineScope，
    // 所以"等 260 毫秒没有第二下才算单击"这件事得用调用方传进来的作用域发出去。
    var pending: Job? = null
    detectTapGestures(
        onTap = {
            pending?.cancel()
            pending = scope.launch {
                delay(TAP_HOLD_MS)
                onSingle()
            }
        },
        onDoubleTap = { offset ->
            pending?.cancel()
            when (PlaybackRules.tapZone(offset.x, size.width.toFloat())) {
                PlaybackRules.TapZone.LEFT -> onLeft()
                PlaybackRules.TapZone.RIGHT -> onRight()
                PlaybackRules.TapZone.CENTER -> onCenter()
            }
        },
    )
}

/**
 * 视频 / 音频：左右滑动切换同目录下的一串文件，每页一个独立播放器实例。
 *
 * 音频只是"没有画面的视频页"——解码、倍速、续播、连播、双击 seek 全走同一条代码，
 * 所以录屏、播客这类只有声音的文件点开就能听，不用甩给别的 App。
 * 播放是前台行为：锁屏或切后台会停，因为后台继续放需要前台服务 + 通知 + 一个运行时权限，
 * 而"装好即用、零弹窗"是这个应用的前提。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoPagerViewer(
    entries: List<VaultEntry>,
    index: Int,
    vm: ViewerViewModel,
    audioOnly: Boolean,
    onIndexChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = index) { entries.size }
    val scope = rememberCoroutineScope()

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
                vm = vm,
                isActive = page == pagerState.currentPage,
                audioOnly = audioOnly,
                onTap = onTap,
                onOpenExternal = onOpenExternal,
                // 播完接下一条：换页由外层做，这里只回"还有没有下一条"
                onFinished = {
                    val next = page + 1
                    if (next < entries.size) {
                        scope.launch { pagerState.animateScrollToPage(next) }
                        true
                    } else {
                        false
                    }
                },
            )
        }
    }
}

@Composable
private fun VideoStage(
    entry: VaultEntry,
    vm: ViewerViewModel,
    isActive: Boolean,
    audioOnly: Boolean,
    onTap: () -> Unit,
    onOpenExternal: () -> Unit,
    onFinished: () -> Boolean,
) {
    val context = LocalContext.current
    val file = rememberEntryFile(entry)
    var videoView by remember(entry.id) { mutableStateOf<VideoView?>(null) }
    // VideoView 不把内部的 MediaPlayer 暴露出来（没有公开的 getMediaPlayer），
    // 而倍速只能在 MediaPlayer 上设，所以从 onPrepared 回调里把它接住存下来。
    var player by remember(entry.id) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(entry.id) { mutableStateOf(false) }
    var playing by remember(entry.id) { mutableStateOf(false) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    var durationMs by remember(entry.id) { mutableLongStateOf(0L) }
    var positionMs by remember(entry.id) { mutableLongStateOf(0L) }
    var dragging by remember(entry.id) { mutableStateOf(false) }
    var scrub by remember(entry.id) { mutableFloatStateOf(0f) }
    var seekWindowEnd by remember(entry.id) { mutableLongStateOf(0L) }
    var resumedFrom by remember(entry.id) { mutableLongStateOf(0L) }
    var flash by remember(entry.id) { mutableStateOf<String?>(null) }
    var rateNote by remember(entry.id) { mutableStateOf<String?>(null) }
    var lastSaved by remember(entry.id) { mutableLongStateOf(0L) }
    val tapScope = rememberCoroutineScope()
    val rate by vm.playbackRate.collectAsStateWithLifecycle()

    /**
     * 把当前进度写进库。
     * 没播起来之前（位置还是 0）不写——否则"刚打开就退出"会把上次的 12:03 冲成 0，
     * 用户看到的就成了"进度又丢了"。
     */
    fun persist() {
        if (durationMs > 0 && positionMs > 0) vm.saveMediaPosition(entry.id, positionMs, durationMs)
    }

    fun seekBy(delta: Long) {
        val view = videoView ?: return
        if (!prepared) return
        val target = PlaybackRules.seekTarget(positionMs, durationMs, delta)
        if (target == positionMs) return
        runCatching { view.seekTo(target.toInt()) }
        positionMs = target
        seekWindowEnd = System.currentTimeMillis() + SEEK_WINDOW_MS
        flash = if (delta < 0) "⏪ ${formatDuration(target)}" else "⏩ ${formatDuration(target)}"
    }

    fun togglePlay() {
        val view = videoView ?: return
        if (!prepared) return
        if (playing) {
            runCatching { view.pause() }
            playing = false
            persist()
        } else {
            runCatching { view.start() }.onSuccess { playing = true }
        }
    }

    /**
     * 变速。系统 MediaPlayer 的两个坑都得绕：
     *  - 暂停态直接改速，个别机型会让它自己开始播——所以改完再把"该不该暂停"按回去；
     *  - 不支持变速的机型会抛异常——那就退回常速并在界面上说清楚，
     *    不能留一个"按了没反应"的按钮让人以为应用卡住。
     */
    fun applyRate(speed: Float) {
        val media = player ?: return
        if (!prepared) return
        val wantPlaying = playing
        runCatching { media.playbackParams = media.playbackParams.setSpeed(speed) }
            .onFailure {
                vm.setPlaybackRate(1f)
                rateNote = "这台机器的解码器不支持变速，已回到常速播放"
            }
        runCatching {
            if (wantPlaying && !media.isPlaying) media.start()
            if (!wantPlaying && media.isPlaying) media.pause()
        }
        playing = runCatching { media.isPlaying }.getOrDefault(wantPlaying)
    }

    fun buildPlayer(ctx: Context): VideoView = VideoView(ctx).apply {
        setOnPreparedListener { media ->
            player = media
            prepared = true
            durationMs = media.duration.coerceAtLeast(1).toLong()
            error = null
        }
        setOnCompletionListener {
            playing = false
            positionMs = durationMs
            persist()
            // 接下一条由外层判断；没有下一条时说明白，别让人以为卡住了
            if (!onFinished()) flash = "这个目录里没有下一个了"
        }
        setOnErrorListener { _, what, extra ->
            prepared = false
            playing = false
            player = null
            error = "这台机器的解码器放不了这个文件（what=$what, extra=$extra）"
            true
        }
        file?.absolutePath?.let { path ->
            runCatching { setVideoPath(path) }.onFailure { error = "无法读取这个文件" }
        } ?: run { error = "文件不存在或仍在回收站中" }
    }.also { videoView = it }

    DisposableEffect(entry.id) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            persist()
            videoView?.let { view -> runCatching { view.stopPlayback() } }
            // stopPlayback 会把 MediaPlayer release 掉，句柄必须一起丢掉，
            // 不然换速时可能拿着一台已经 release 的播放器去调，直接 IllegalStateException
            player = null
            prepared = false
            videoView = null
        }
    }

    LaunchedEffect(isActive, prepared) {
        val view = videoView ?: return@LaunchedEffect
        if (!isActive) {
            if (playing) {
                runCatching { view.pause() }
                playing = false
                persist()
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

    // 续播：prepared 之后回到上次那一毫秒。3 秒以内不值得跳，跳过去只会看见黑屏。
    LaunchedEffect(prepared, entry.id) {
        if (!prepared) return@LaunchedEffect
        val saved = vm.savedMediaPosition(entry.id) ?: return@LaunchedEffect
        if (!PlaybackRules.shouldResume(saved.positionMillis, saved.durationMillis)) return@LaunchedEffect
        runCatching { videoView?.seekTo(saved.positionMillis.toInt()) }
        positionMs = saved.positionMillis
        seekWindowEnd = System.currentTimeMillis() + SEEK_WINDOW_MS
        resumedFrom = saved.positionMillis
    }

    LaunchedEffect(rate, prepared) { if (prepared) applyRate(rate) }

    // "从 12:03 继续"挂四秒自己收起来，不挡住画面
    LaunchedEffect(resumedFrom) {
        if (resumedFrom > 0) {
            delay(4_000)
            resumedFrom = 0L
        }
    }

    LaunchedEffect(playing, dragging) {
        while (playing && !dragging) {
            videoView?.let { view ->
                val live = view.currentPosition.toLong()
                positionMs = if (System.currentTimeMillis() < seekWindowEnd) live
                else live.coerceAtLeast(positionMs)
                val now = System.currentTimeMillis()
                if (now - lastSaved > SAVE_EVERY_MS) {
                    lastSaved = now
                    persist()
                }
            }
            delay(300)
        }
    }

    LaunchedEffect(flash) {
        if (flash != null) {
            delay(800)
            flash = null
        }
    }

    LaunchedEffect(rateNote) {
        if (rateNote != null) {
            delay(3_000)
            rateNote = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (audioOnly) {
            // 播放器照旧（它内部就是 MediaPlayer），只是不显示那块永远黑色的画面
            AndroidView(
                factory = ::buildPlayer,
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .tapZones(
                        tapScope,
                        entry.id,
                        onSingle = onTap,
                        onLeft = { seekBy(-SKIP_MS) },
                        onRight = { seekBy(SKIP_MS) },
                        onCenter = ::togglePlay,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
                    Icon(
                        Icons.Filled.Audiotrack,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        entry.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error ?: "应用内播放：锁屏或切到后台会停（后台继续放需要额外权限与通知）",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            AndroidView(
                factory = ::buildPlayer,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
                    .tapZones(
                        tapScope,
                        entry.id,
                        onSingle = onTap,
                        onLeft = { seekBy(-SKIP_MS) },
                        onRight = { seekBy(SKIP_MS) },
                        onCenter = ::togglePlay,
                    ),
            )
        }

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

        // 双击 seek 的反馈：跳完显示目标时间，不到一秒自己消失
        flash?.let { text ->
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        if (resumedFrom > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("从 ${formatDuration(resumedFrom)} 继续", color = Color.White, maxLines = 1)
                Text(
                    "从头播",
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable {
                            videoView?.let { runCatching { it.seekTo(0) } }
                            positionMs = 0L
                            seekWindowEnd = System.currentTimeMillis() + SEEK_WINDOW_MS
                            resumedFrom = 0L
                            vm.clearMediaPosition(entry.id)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        rateNote?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 152.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        ) {
            if (!audioOnly) {
                Text(
                    entry.name,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("倍速", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                PlaybackRules.RATES.forEach { option ->
                    Text(
                        if (option == 1f) "常速" else "${option}x",
                        color = if (rate == option) Color.Black else Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (rate == option) Color.White.copy(alpha = 0.92f)
                                else Color.White.copy(alpha = 0.16f),
                            )
                            .clickable { vm.setPlaybackRate(option) }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(enabled = prepared) { togglePlay() },
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
                        seekWindowEnd = System.currentTimeMillis() + SEEK_WINDOW_MS
                        dragging = false
                        persist()
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
