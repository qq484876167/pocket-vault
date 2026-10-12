package com.qoder.pocketvault.ui.viewer

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qoder.pocketvault.core.ReaderLayout
import com.qoder.pocketvault.util.TextBook
import com.qoder.pocketvault.util.TextEncoding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class ReaderSheet { NONE, TOC, BOOKMARK, STYLE, SEARCH }

/**
 * 纯文本阅读器。
 * 交互：竖向滚动、点屏幕下半/上半翻一屏、左右滑切上/下一章；
 * 可调：字号、行距、页边距、四套配色、编码；可记：进度（章 + 章内段落）与书签。
 * 刻意不做"精确算字的真分页"——换字号就要重排、图片与空行断页处理复杂，滚动+翻屏已够用。
 */
@Composable
fun TextViewer(
    vm: ViewerViewModel,
    chrome: Boolean,
    onTap: () -> Unit,
    onBack: () -> Unit,
) {
    val book by vm.book.collectAsState()
    val loading by vm.bookLoading.collectAsState()
    val error by vm.bookError.collectAsState()
    val chapterIndex by vm.chapterIndex.collectAsState()
    val paragraphIndex by vm.paragraphIndex.collectAsState()
    val resumeParagraph by vm.resumeParagraph.collectAsState()
    val paragraphs by vm.paragraphs.collectAsState()
    val fontSize by vm.fontSize.collectAsState()
    val lineSpacing by vm.lineSpacing.collectAsState()
    val marginDp by vm.margin.collectAsState()
    val indent by vm.indent.collectAsState()
    val paragraphGap by vm.paragraphGap.collectAsState()
    val fontFamilyIndex by vm.fontFamily.collectAsState()
    val fontWeightIndex by vm.fontWeight.collectAsState()
    val justify by vm.justify.collectAsState()
    val maxChars by vm.maxCharsPerLine.collectAsState()
    val pageAnim by vm.pageAnim.collectAsState()
    val volumePaging by vm.volumePaging.collectAsState()
    val autoScrollLevel by vm.autoScroll.collectAsState()
    val progressMode by vm.progressMode.collectAsState()
    val sessionSeconds by vm.sessionSeconds.collectAsState()
    val marks by vm.bookmarks.collectAsState()
    val themeIndex by vm.themeIndex.collectAsState()

    val theme = ReaderTheme.values().getOrElse(themeIndex) { ReaderTheme.PARCHMENT }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var sheet by remember { mutableStateOf(ReaderSheet.NONE) }
    var bookPercent by remember(chapterIndex) { mutableIntStateOf(0) }
    val swipeThreshold = with(density) { 88.dp.toPx() }

    val readerFocus = remember { FocusRequester() }

    // 阅读时保持常亮并开始计时；离开时停表、把当前位置补写进库，别指望那 250ms 的防抖来得及
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm.startReadingTimer()
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vm.stopReadingTimer()
            vm.flushProgress()
        }
    }

    // 音量键要先进 Compose 的按键分发，得有节点持有焦点才行；开了"音量键翻页"才去抢焦点，
    // 不然按音量键应该是系统的音量条，不是这本书在翻页。
    LaunchedEffect(volumePaging) {
        if (volumePaging) runCatching { readerFocus.requestFocus() }
    }

    /** 可视区高度（像素）。翻屏与自动滚屏都按它换算。 */
    fun viewportHeightPx(): Float {
        val info = listState.layoutInfo
        return (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
    }

    /**
     * 正文可用的宽度（像素）：屏宽减去左右页边距。
     * 屏宽在组合期取好（LocalConfiguration 只能在 @Composable 里读），函数本身只是换算。
     */
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    fun viewportWidthPx(): Float {
        val available = (screenWidthDp - (marginDp * 2).dp).coerceAtLeast(0.dp)
        return with(density) { available.toPx() }
    }

    fun turnScreen(direction: Int) {
        val distance = viewportHeightPx() * 0.88f * direction
        scope.launch {
            if (pageAnim == 1) listState.animateScrollBy(distance) else listState.scrollBy(distance)
        }
    }

    // 自动滚屏：档位 0 就是完全停，不留"极慢地一直在滚"这种关不掉的错觉。
    LaunchedEffect(autoScrollLevel) {
        if (autoScrollLevel <= 0) return@LaunchedEffect
        while (isActive) {
            listState.scrollBy(ReaderLayout.autoScrollPxPerFrame(autoScrollLevel, viewportHeightPx()))
            delay(AUTO_SCROLL_FRAME_MS)
        }
    }

    LaunchedEffect(resumeParagraph, paragraphs.size) {
        val target = resumeParagraph
        if (target in 0 until paragraphs.size) {
            listState.scrollToItem(target)
        }
        // 复位必须无条件：漏一次 _resumeParagraph 就永久 >= 0，
        // 之后 onParagraphRead 会一直早退，整场阅读都不再记录进度。
        vm.consumeResume()
    }

    // 段落位置按 150ms 采样上报：正确性不靠"每一个都记"，离开时 DisposableEffect /
    // onCleared 都会 flushProgress() 把最后一位置写下去。
    // snapshotFlow 本身是合并式的，collect 里睡 150ms 就等于 sample(150)，
    // 又不用碰 kotlinx.coroutines.flow.sample 那个 @FlowPreview。
    LaunchedEffect(listState, chapterIndex, paragraphs.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                val safe = index.coerceAtMost((paragraphs.size - 1).coerceAtLeast(0))
                vm.onParagraphRead(safe)
                bookPercent = book.percentRead(chapterIndex, safe, paragraphs.size)
                delay(SAMPLE_MS)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .pointerInput(chapterIndex) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(dragged) > swipeThreshold) vm.stepChapter(if (dragged < 0f) 1 else -1)
                        dragged = 0f
                    },
                    onHorizontalDrag = { _, amount -> dragged += amount },
                )
            },
    ) {
        AnimatedVisibility(visible = chrome) {
            ReaderTopBar(
                title = book?.title.orEmpty(),
                chapter = book?.chapterAt(chapterIndex)?.title.orEmpty(),
                theme = theme,
                bookmarked = marks.any { it.chapterIndex == chapterIndex && it.paragraphIndex == paragraphIndex },
                onBack = onBack,
                onToc = { sheet = ReaderSheet.TOC },
                onBookmark = { sheet = ReaderSheet.BOOKMARK },
                onStyle = { sheet = ReaderSheet.STYLE },
                onSearch = { sheet = ReaderSheet.SEARCH },
                onToggleBookmark = vm::toggleBookmark,
            )
        }

        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(theme.background),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = theme.accent, strokeWidth = 2.5.dp) }

            error != null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(theme.background)
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = theme.muted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("顶栏「Aa」里可以换编码再试一次", color = theme.muted, style = MaterialTheme.typography.bodySmall)
                }
            }

            // 长按可选中/复制：SelectionContainer 只在长按与拖动时接管手势，
            // 短按仍然由下面 LazyColumn 的 detectTapGestures 翻屏，两者不冲突。
            else -> SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = marginDp.dp)
                        .focusRequester(readerFocus)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            // 音量下 = 往后翻一屏，音量上 = 往前翻一屏。没开这个选项就原样放行给系统。
                            if (!volumePaging || event.type != KeyEventType.KeyDown) false
                            else when {
                                event.key == Key.VolumeDown -> {
                                    turnScreen(1)
                                    true
                                }

                                event.key == Key.VolumeUp -> {
                                    turnScreen(-1)
                                    true
                                }

                                else -> false
                            }
                        }
                        .pointerInput(paragraphs.size, pageAnim) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val height = viewportHeightPx()
                                    when {
                                        offset.y > height * 0.62f -> turnScreen(1)
                                        offset.y < height * 0.38f -> turnScreen(-1)
                                        else -> onTap()
                                    }
                                },
                                // 双击 = 打开目录：翻书时"跳到哪一章"是最高频的跳转
                                onDoubleTap = { sheet = ReaderSheet.TOC },
                            )
                        },
                ) {
                    items(count = paragraphs.size) { position ->
                        Text(
                            // 首行缩进用全角空格：它就等于"一个汉字宽"，换字体也不会歪
                            text = ReaderLayout.indentPrefix(indent) + paragraphs[position],
                            color = theme.text,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * lineSpacing).sp,
                            fontFamily = when (fontFamilyIndex) {
                                1 -> FontFamily.Serif
                                2 -> FontFamily.Monospace
                                else -> FontFamily.SansSerif
                            },
                            fontWeight = when (fontWeightIndex) {
                                1 -> FontWeight.Medium
                                2 -> FontWeight.SemiBold
                                3 -> FontWeight.Bold
                                else -> FontWeight.Normal
                            },
                            textAlign = if (justify) TextAlign.Justify else TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                // 每行字数上限：平板 / 折叠屏上一行拉到五六十个字会读得累
                                .then(
                                    if (maxChars <= 0) Modifier
                                    else Modifier.widthIn(
                                        max = with(density) {
                                            ReaderLayout.maxContentWidthPx(
                                                viewportWidthPx = viewportWidthPx(),
                                                maxCharsPerLine = maxChars,
                                                fontSizePx = with(density) { fontSize.sp.toPx() },
                                            ).dp
                                        },
                                    ),
                                )
                                .padding(bottom = ReaderLayout.paragraphGapDp(fontSize, paragraphGap).dp),
                        )
                    }
                    item {
                        Text(
                            text = if (chapterIndex < (book?.chapters?.lastIndex ?: 0)) "本章完 · 左滑继续" else "全书完",
                            color = theme.muted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 22.dp),
                        )
                    }
                    item { Spacer(Modifier.height(56.dp)) }
                }
            }
        }

        AnimatedVisibility(visible = chrome) {
            ReaderFooter(
                chapterIndex = chapterIndex,
                chapterCount = book?.chapters?.size ?: 0,
                paragraphIndex = paragraphIndex,
                paragraphCount = paragraphs.size,
                bookPercent = bookPercent,
                progressMode = progressMode,
                remainingMinutes = ReaderLayout.remainingMinutes(sessionSeconds, bookPercent),
                theme = theme,
                onPrev = { vm.stepChapter(-1) },
                onNext = { vm.stepChapter(1) },
            )
        }
    }

    when (sheet) {
        ReaderSheet.TOC -> TocSheet(book, chapterIndex, paragraphIndex, theme, onSelect = { vm.selectChapter(it) }, onDismiss = { sheet = ReaderSheet.NONE })
        ReaderSheet.BOOKMARK -> BookmarkSheet(book, marks, theme, onJump = { vm.jumpToBookmark(it) }, onDelete = { vm.removeBookmarkAt(it) }, onDismiss = { sheet = ReaderSheet.NONE })
        ReaderSheet.STYLE -> StyleSheet(vm, book, theme, onDismiss = { sheet = ReaderSheet.NONE })
        ReaderSheet.SEARCH -> SearchSheet(
            vm,
            book,
            theme,
            onJump = { hit ->
                vm.jumpTo(hit.chapterIndex, hit.paragraphIndex)
                sheet = ReaderSheet.NONE
            },
            onDismiss = { sheet = ReaderSheet.NONE },
        )
        ReaderSheet.NONE -> Unit
    }
}

/** 全书进度：按章节字符区间加权，不依赖字号行距，所以换排版也不会错位。 */
private fun TextBook?.percentRead(chapterIndex: Int, paragraph: Int, paragraphCount: Int): Int {
    val book = this ?: return 0
    val chapter = book.chapterAt(chapterIndex) ?: return 0
    if (book.totalChars <= 0) return 0
    val inside = if (paragraphCount > 0) (paragraph + 1f) / paragraphCount else 0f
    val consumed = chapter.charStart + chapter.length * inside
    return (consumed / book.totalChars.toFloat() * 100f).toInt().coerceIn(0, 100)
}

@Composable
private fun ReaderTopBar(
    title: String,
    chapter: String,
    theme: ReaderTheme,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onBookmark: () -> Unit,
    onStyle: () -> Unit,
    onSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .background(theme.bar)
            .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton("返回", theme, onBack)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(chapter, color = theme.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        BarButton("目录", theme, onToc)
        BarButton(if (bookmarked) "已标记" else "标记", theme, onToggleBookmark)
        BarButton("书签", theme, onBookmark)
        BarButton("搜索", theme, onSearch)
        BarButton("Aa", theme, onStyle)
    }
}

@Composable
private fun BarButton(label: String, theme: ReaderTheme, onClick: () -> Unit) {
    Text(
        label,
        color = theme.accent,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun ReaderFooter(
    chapterIndex: Int,
    chapterCount: Int,
    paragraphIndex: Int,
    paragraphCount: Int,
    bookPercent: Int,
    progressMode: Int,
    remainingMinutes: Int?,
    theme: ReaderTheme,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.bar)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "上一章",
            color = if (chapterIndex > 0) theme.accent else theme.muted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clickable(enabled = chapterIndex > 0, onClick = onPrev)
                .padding(end = 12.dp),
        )
        Text(
            buildString {
                append("第 ${chapterIndex + 1}/$chapterCount 章 · 第 ${paragraphIndex + 1}/${paragraphCount} 段")
                val label = ReaderLayout.progressLabel(progressMode, bookPercent, remainingMinutes)
                if (label.isNotEmpty()) append(" · ").append(label)
            },
            color = theme.muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text(
            "下一章",
            color = if (chapterIndex < chapterCount - 1) theme.accent else theme.muted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clickable(enabled = chapterIndex < chapterCount - 1, onClick = onNext)
                .padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSheet(title: String, theme: ReaderTheme, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.background) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = theme.text)
            Spacer(Modifier.height(6.dp))
            content()
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun TocSheet(
    book: TextBook?,
    current: Int,
    paragraphIndex: Int,
    theme: ReaderTheme,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val chapters = book?.chapters.orEmpty()
    ReaderSheet("目录（${chapters.size} 节）", theme, onDismiss) {
        LazyColumn(modifier = Modifier.height(380.dp)) {
            items(count = chapters.size) { index ->
                val chapter = chapters[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chapter.title,
                        color = if (index == current) theme.accent else theme.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (index == current) {
                        Text("读到第 ${paragraphIndex + 1} 段", color = theme.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkSheet(
    book: TextBook?,
    marks: List<com.qoder.pocketvault.data.db.ReaderBookmark>,
    theme: ReaderTheme,
    onJump: (com.qoder.pocketvault.data.db.ReaderBookmark) -> Unit,
    onDelete: (com.qoder.pocketvault.data.db.ReaderBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    ReaderSheet("书签（${marks.size}）", theme, onDismiss) {
        if (marks.isEmpty()) {
            Text("还没有书签：读到关键处点顶栏「标记」即可，位置按章节+段落保存。", color = theme.muted, style = MaterialTheme.typography.bodySmall)
            return@ReaderSheet
        }
        LazyColumn(modifier = Modifier.height(340.dp)) {
            items(count = marks.size) { position ->
                val mark = marks[position]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onJump(mark) },
                    ) {
                        Text(
                            "${book?.chapterAt(mark.chapterIndex)?.title ?: "第 ${mark.chapterIndex + 1} 章"} · 第 ${mark.paragraphIndex + 1} 段",
                            color = theme.accent,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(mark.label, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "删除",
                        color = theme.muted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clickable { onDelete(mark) }
                            .padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StyleSheet(vm: ViewerViewModel, book: TextBook?, theme: ReaderTheme, onDismiss: () -> Unit) {
    val fontSize by vm.fontSize.collectAsState()
    val lineSpacing by vm.lineSpacing.collectAsState()
    val marginDp by vm.margin.collectAsState()
    val themeIndex by vm.themeIndex.collectAsState()
    val indent by vm.indent.collectAsState()
    val paragraphGap by vm.paragraphGap.collectAsState()
    val fontFamilyIndex by vm.fontFamily.collectAsState()
    val fontWeightIndex by vm.fontWeight.collectAsState()
    val justify by vm.justify.collectAsState()
    val maxChars by vm.maxCharsPerLine.collectAsState()
    val pageAnim by vm.pageAnim.collectAsState()
    val volumePaging by vm.volumePaging.collectAsState()
    val autoScrollLevel by vm.autoScroll.collectAsState()
    val progressMode by vm.progressMode.collectAsState()

    ReaderSheet("排版与编码", theme, onDismiss) {
        LabeledSlider("字号 ${fontSize.toInt()} sp", 13f..32f, fontSize, theme, vm::setFontSize)
        LabeledSlider("行距 ${"%.2f".format(lineSpacing)}", 1.3f..2.4f, lineSpacing, theme, vm::setLineSpacing)
        LabeledSlider("段间距 +${"%.2f".format(paragraphGap)} 倍字号", 0f..1.5f, paragraphGap, theme, vm::setParagraphGap)
        LabeledSlider("页边距 ${marginDp} dp", 8f..40f, marginDp.toFloat(), theme) { vm.setMargin(it.toInt()) }

        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            "每行字数 ${if (maxChars == 0) "不限" else "$maxChars"}",
            listOf("不限", "24", "30", "36", "42"),
            MAX_CHARS.indexOf(maxChars).coerceAtLeast(0),
            theme,
        ) { vm.setMaxCharsPerLine(MAX_CHARS[it]) }
        ChoiceRow("首行缩进", listOf("不缩", "1 字", "2 字", "3 字", "4 字"), indent, theme, vm::setIndent)
        ChoiceRow("字体", listOf("系统", "衬线", "等宽"), fontFamilyIndex, theme, vm::setFontFamily)
        ChoiceRow("字重", listOf("常规", "中等", "半粗", "粗"), fontWeightIndex, theme, vm::setFontWeight)
        ChoiceRow("对齐", listOf("左齐", "两端"), if (justify) 1 else 0, theme) { vm.setJustify(it == 1) }
        ChoiceRow("翻页动画", listOf("直接落位", "平滑滚动"), pageAnim, theme, vm::setPageAnim)
        ChoiceRow("音量键翻页", listOf("关", "开"), if (volumePaging) 1 else 0, theme) { vm.setVolumePaging(it == 1) }
        ChoiceRow("自动滚屏", listOf("关", "很慢", "慢", "中", "快", "很快"), autoScrollLevel, theme, vm::setAutoScroll)
        ChoiceRow("进度显示", listOf("不显示", "百分比", "剩余时间"), progressMode, theme, vm::setProgressMode)
        Text(
            "音量键翻页打开后，音量键就归这本书用了（阅读页内）；自动滚屏随时点一下屏幕可停。",
            color = theme.muted,
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text("配色", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            ReaderTheme.values().forEachIndexed { index, option ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .border(
                            width = if (index == themeIndex) 2.dp else 0.dp,
                            color = if (index == themeIndex) theme.accent else Color.Transparent,
                        )
                        .clickable { vm.setTheme(index) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .background(option.background),
                    ) {
                        Text("文 Aa", color = option.text, modifier = Modifier.padding(5.dp))
                    }
                    Text(option.label, color = theme.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("编码（当前：${book?.encoding?.label ?: "未加载"}）", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            TextEncoding.values().forEach { encoding ->
                Text(
                    encoding.label,
                    color = theme.accent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable { vm.setEncoding(encoding) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    range: ClosedFloatingPointRange<Float>,
    value: Float,
    theme: ReaderTheme,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = theme.muted, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent),
        )
    }
}

/** "每行字数"可选的几档；0 表示不限（用满页宽）。 */
private val MAX_CHARS = listOf(0, 24, 30, 36, 42)

/** 一排互斥选项。排版偏好全是"选一个"的形态，用滑块反而看不出档位。 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: Int,
    theme: ReaderTheme,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = theme.muted, style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
            options.forEachIndexed { index, text ->
                Text(
                    text,
                    color = if (index == selected) theme.background else theme.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .background(
                            if (index == selected) theme.accent else theme.bar,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onPick(index) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchSheet(
    vm: ViewerViewModel,
    book: TextBook?,
    theme: ReaderTheme,
    onJump: (TextBook.SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    val query by vm.searchQuery.collectAsState()
    val results by vm.searchResults.collectAsState()
    val busy by vm.searchBusy.collectAsState()
    ReaderSheet("全文搜索", theme, onDismiss) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::search,
            label = { Text("输入关键字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        when {
            busy -> Text("正在搜索…", color = theme.muted, style = MaterialTheme.typography.bodySmall)
            query.isNotBlank() && results.isEmpty() -> Text("没有找到匹配内容。", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(modifier = Modifier.height(300.dp)) {
            items(count = results.size) { position ->
                val hit = results[position]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(hit) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        "${book?.chapterAt(hit.chapterIndex)?.title ?: "第 ${hit.chapterIndex + 1} 章"} · 第 ${hit.paragraphIndex + 1} 段",
                        color = theme.accent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(hit.preview, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 滚动回报的最小间隔：甩动时不把 prefs 镜像、协程与重组按段落逐个触发。 */
private const val SAMPLE_MS = 150L

/** 自动滚屏一帧多少毫秒。 */
private const val AUTO_SCROLL_FRAME_MS = 16L
