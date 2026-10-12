package com.qoder.pocketvault.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qoder.pocketvault.AppGraph
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.LastReading
import com.qoder.pocketvault.data.db.MediaPosition
import com.qoder.pocketvault.data.db.PdfBookmark
import com.qoder.pocketvault.data.db.ReaderBookmark
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.data.userMessage
import com.qoder.pocketvault.ui.vm.rethrowIfCancelled
import com.qoder.pocketvault.util.PdfDocument
import com.qoder.pocketvault.util.TextBook
import com.qoder.pocketvault.util.TextBookLoader
import com.qoder.pocketvault.util.TextEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewerMode { PAGER, STRIP }

enum class ViewerSubject { IMAGE, VIDEO, AUDIO, PDF, TEXT, UNSUPPORTED }

/** 查看器/阅读器的状态。图片与视频用序列，文本按章切分并记住位置。 */
class ViewerViewModel(
    private val graph: AppGraph,
    private val entryId: Long,
) : ViewModel() {

    private val repo = graph.repo

    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index

    private val _mode = MutableStateFlow(ViewerMode.STRIP)
    val mode: StateFlow<ViewerMode> = _mode

    private val _rotation = MutableStateFlow(0)
    val rotation: StateFlow<Int> = _rotation

    private val _subject = MutableStateFlow(ViewerSubject.UNSUPPORTED)
    val subject: StateFlow<ViewerSubject> = _subject

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    // PDF ------------------------------------------------------------------
    private val _pdf = MutableStateFlow<PdfDocument?>(null)
    private val _pdfPageCount = MutableStateFlow(0)
    val pdfPageCount: StateFlow<Int> = _pdfPageCount

    // 文本 ------------------------------------------------------------------
    private val _book = MutableStateFlow<TextBook?>(null)
    val book: StateFlow<TextBook?> = _book

    private val _bookLoading = MutableStateFlow(false)
    val bookLoading: StateFlow<Boolean> = _bookLoading

    private val _bookError = MutableStateFlow<String?>(null)
    val bookError: StateFlow<String?> = _bookError

    private val _paragraphs = MutableStateFlow<List<String>>(emptyList())
    val paragraphs: StateFlow<List<String>> = _paragraphs

    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex

    /** 章内段落序号：换字号换行距后依然能定位，所以进度按它存。 */
    private val _paragraphIndex = MutableStateFlow(0)
    val paragraphIndex: StateFlow<Int> = _paragraphIndex

    private val _resumeParagraph = MutableStateFlow(-1)
    val resumeParagraph: StateFlow<Int> = _resumeParagraph

    private val _fontSize = MutableStateFlow(graph.prefs.readingFontSize)
    val fontSize: StateFlow<Float> = _fontSize

    private val _lineSpacing = MutableStateFlow(graph.prefs.readingLineSpacing)
    val lineSpacing: StateFlow<Float> = _lineSpacing

    private val _themeIndex = MutableStateFlow(graph.prefs.readingTheme)
    val themeIndex: StateFlow<Int> = _themeIndex

    private val _margin = MutableStateFlow(graph.prefs.readingMargin)
    val margin: StateFlow<Int> = _margin

    // 排版细项：都是"看着舒服不一样"的偏好，全部持久化，改动即时生效、不重新解析文件。
    private val _indent = MutableStateFlow(graph.prefs.readingIndent)
    val indent: StateFlow<Int> = _indent

    private val _paragraphGap = MutableStateFlow(graph.prefs.readingParagraphGap)
    val paragraphGap: StateFlow<Float> = _paragraphGap

    private val _fontFamily = MutableStateFlow(graph.prefs.readingFontFamily)
    val fontFamily: StateFlow<Int> = _fontFamily

    private val _fontWeight = MutableStateFlow(graph.prefs.readingFontWeight)
    val fontWeight: StateFlow<Int> = _fontWeight

    private val _justify = MutableStateFlow(graph.prefs.readingJustify)
    val justify: StateFlow<Boolean> = _justify

    private val _maxChars = MutableStateFlow(graph.prefs.readingMaxCharsPerLine)
    val maxCharsPerLine: StateFlow<Int> = _maxChars

    private val _pageAnim = MutableStateFlow(graph.prefs.readingPageAnim)
    val pageAnim: StateFlow<Int> = _pageAnim

    private val _volumePaging = MutableStateFlow(graph.prefs.readingVolumePaging)
    val volumePaging: StateFlow<Boolean> = _volumePaging

    private val _autoScroll = MutableStateFlow(graph.prefs.readingAutoScroll)
    val autoScroll: StateFlow<Int> = _autoScroll

    private val _progressMode = MutableStateFlow(graph.prefs.readingProgressMode)
    val progressMode: StateFlow<Int> = _progressMode

    fun setIndent(chars: Int) {
        val coerced = chars.coerceIn(0, 4)
        if (coerced == _indent.value) return
        _indent.value = coerced
        graph.prefs.readingIndent = coerced
    }

    fun setParagraphGap(factor: Float) {
        val coerced = factor.coerceIn(0f, 1.5f)
        if (coerced == _paragraphGap.value) return
        _paragraphGap.value = coerced
        graph.prefs.readingParagraphGap = coerced
    }

    fun setFontFamily(index: Int) {
        val coerced = index.coerceIn(0, 2)
        if (coerced == _fontFamily.value) return
        _fontFamily.value = coerced
        graph.prefs.readingFontFamily = coerced
    }

    fun setFontWeight(index: Int) {
        val coerced = index.coerceIn(0, 3)
        if (coerced == _fontWeight.value) return
        _fontWeight.value = coerced
        graph.prefs.readingFontWeight = coerced
    }

    fun setJustify(on: Boolean) {
        _justify.value = on
        graph.prefs.readingJustify = on
    }

    fun setMaxCharsPerLine(chars: Int) {
        val coerced = chars.coerceIn(0, 60)
        if (coerced == _maxChars.value) return
        _maxChars.value = coerced
        graph.prefs.readingMaxCharsPerLine = coerced
    }

    fun setPageAnim(mode: Int) {
        val coerced = mode.coerceIn(0, 1)
        if (coerced == _pageAnim.value) return
        _pageAnim.value = coerced
        graph.prefs.readingPageAnim = coerced
    }

    fun setVolumePaging(on: Boolean) {
        _volumePaging.value = on
        graph.prefs.readingVolumePaging = on
    }

    fun setAutoScroll(level: Int) {
        val coerced = level.coerceIn(0, 5)
        if (coerced == _autoScroll.value) return
        _autoScroll.value = coerced
        graph.prefs.readingAutoScroll = coerced
    }

    fun setProgressMode(mode: Int) {
        val coerced = mode.coerceIn(0, 2)
        if (coerced == _progressMode.value) return
        _progressMode.value = coerced
        graph.prefs.readingProgressMode = coerced
    }

    // ---------------------------------------------------------------- 阅读统计

    /** 这一场读了多久（秒）。剩余时间按它来估，比拿"今天累计"估得更贴切。 */
    private val _sessionSeconds = MutableStateFlow(0)
    val sessionSeconds: StateFlow<Int> = _sessionSeconds

    private var readingTimerJob: Job? = null
    private var unbankedSeconds = 0
    private var pendingBlocks = 0

    /**
     * 开始计时：只在阅读页真的活着的时候跑（界面 DisposableEffect 负责停）。
     * 每 10 秒加一格，攒够 60 秒才写一次库——频繁小事务对 flash 没有意义。
     */
    fun startReadingTimer() {
        if (readingTimerJob?.isActive == true) return
        readingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(STAT_TICK_SECONDS * 10L)
                _sessionSeconds.value += STAT_TICK_SECONDS
                unbankedSeconds += STAT_TICK_SECONDS
                if (unbankedSeconds >= 60) bankReadingTime()
            }
        }
    }

    fun stopReadingTimer() {
        readingTimerJob?.cancel()
        readingTimerJob = null
        bankReadingTime()
    }

    private fun bankReadingTime() {
        val seconds = unbankedSeconds
        val blocks = pendingBlocks
        if (seconds <= 0 && blocks <= 0) return
        unbankedSeconds = 0
        pendingBlocks = 0
        graph.ioScope.launch {
            runCatching { repo.addReadingTime(entryId, seconds, blocks) }
        }
    }

    /** 读到哪一段了：顺便记下往前推进了多少段，统计里就是"今天读了多少处"。 */
    private fun noteBlocksAdvanced(from: Int, to: Int) {
        if (to > from) pendingBlocks += to - from
    }

    private val _bookmarks = MutableStateFlow<List<ReaderBookmark>>(emptyList())
    val bookmarks: StateFlow<List<ReaderBookmark>> = _bookmarks

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<TextBook.SearchHit>>(emptyList())
    val searchResults: StateFlow<List<TextBook.SearchHit>> = _searchResults

    /** 防抖期间界面不该闪「没有找到匹配内容」。 */
    private val _searchBusy = MutableStateFlow(false)
    val searchBusy: StateFlow<Boolean> = _searchBusy

    val theme: ReaderTheme get() = ReaderTheme.values().getOrElse(_themeIndex.value) { ReaderTheme.PARCHMENT }

    val current: VaultEntry? get() = _entries.value.getOrNull(_index.value)

    /** 视频与音频没有“翻页 / 连续滚动”两种模式的概念。 */
    val canToggleMode: Boolean
        get() = _subject.value != ViewerSubject.VIDEO && _subject.value != ViewerSubject.AUDIO

    // ------------------------------------------------------------ 播放位置与倍速

    /** 倍速对视频和音频共用一档：换了文件也保持上次的选择。 */
    private val _playbackRate = MutableStateFlow(graph.prefs.playbackRate)
    val playbackRate: StateFlow<Float> = _playbackRate

    fun setPlaybackRate(rate: Float) {
        val coerced = rate.coerceIn(0.5f, 2f)
        if (coerced == _playbackRate.value) return
        _playbackRate.value = coerced
        graph.prefs.playbackRate = coerced
    }

    /**
     * 上次看到第几毫秒的原始记录。
     * "要不要真的续上"（太短不值得跳、看到 95% 就当看完）由
     * [com.qoder.pocketvault.core.PlaybackRules.shouldResume] 判，规则只有一处。
     */
    suspend fun savedMediaPosition(entryId: Long): MediaPosition? =
        runCatching { repo.mediaPosition(entryId) }.getOrNull()

    /**
     * 记下播放进度。走进程级作用域而不是 viewModelScope：滑到下一条、退出页面这些时刻
     * viewModelScope 可能已经在取消，而那正是最需要把进度存下来的时刻。
     */
    fun saveMediaPosition(entryId: Long, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L || durationMs <= 0L) return
        graph.ioScope.launch {
            runCatching { repo.saveMediaPosition(entryId, positionMs, durationMs) }
        }
    }

    /**
     * 「从头播」：把这条记录抹掉。
     * 写成 0 而不是删掉，会被下一次"刚准备好还没播就退出"的保存覆盖回来，语义也更绕。
     */
    fun clearMediaPosition(entryId: Long) {
        graph.ioScope.launch { runCatching { repo.clearMediaPosition(entryId) } }
    }

    // ---------------------------------------------------------------- PDF 书签与夜间反色

    private val _pdfMarks = MutableStateFlow<List<PdfBookmark>>(emptyList())
    val pdfMarks: StateFlow<List<PdfBookmark>> = _pdfMarks

    /** 夜间反色：只是显示层套一张反色矩阵，不重新渲染页面，所以开关是即时的。 */
    private val _nightInvert = MutableStateFlow(graph.prefs.pdfInvert)
    val nightInvert: StateFlow<Boolean> = _nightInvert

    fun toggleNightInvert() {
        val next = !_nightInvert.value
        _nightInvert.value = next
        graph.prefs.pdfInvert = next
    }

    private suspend fun loadPdfMarks(entryId: Long) {
        _pdfMarks.value = runCatching { repo.pdfBookmarks(entryId) }
            .onFailure { it.rethrowIfCancelled() }
            .getOrDefault(emptyList())
    }

    /** 给当前这一页加书签。同一页重复点只算刷新标签，不会堆重复行。 */
    fun addPdfBookmark() {
        val page = _index.value
        viewModelScope.launch {
            val outcome = runCatching { repo.addPdfBookmark(entryId, page, "") }
                .onFailure { it.rethrowIfCancelled() }
            if (outcome.isFailure) _message.value = "书签没存上：${outcome.exceptionOrNull()?.userMessage()}"
            else loadPdfMarks(entryId)
        }
    }

    fun removePdfBookmark(page: Int) {
        viewModelScope.launch {
            val outcome = runCatching { repo.removePdfBookmark(entryId, page) }
                .onFailure { it.rethrowIfCancelled() }
            if (outcome.isFailure) _message.value = "书签没删掉：${outcome.exceptionOrNull()?.userMessage()}"
            else loadPdfMarks(entryId)
        }
    }

    /** 书签跳转：页码越界就夹一下，别让列表里残留的旧页码把界面弄乱。 */
    fun jumpToPdfPage(page: Int) {
        onIndexChanged(page.coerceIn(0, (_pdfPageCount.value - 1).coerceAtLeast(0)))
    }

    private var persistJob: Job? = null
    private var searchJob: Job? = null
    private var chapterJob: Job? = null
    @Volatile
    private var chapterToken = 0
    private var resumeGuard: Job? = null
    private var encodingOverride: TextEncoding? = null

    init {
        viewModelScope.launch {
            val loaded = runCatching { repo.entryById(entryId) }.onFailure { it.rethrowIfCancelled() }
            // 读库出错和"真的没有这条"不能都报"已不存在"，后者会把用户支去重新导入
            if (loaded.isFailure) {
                _message.value = "读取索引失败：${loaded.exceptionOrNull()?.userMessage()}"
                _ready.value = true
                return@launch
            }
            val entry = loaded.getOrNull()
            if (entry == null) {
                _message.value = "该项目已不存在"
                _ready.value = true
                return@launch
            }
            val sequence = runCatching { repo.viewerSequence(entry) }.getOrDefault(listOf(entry))
            _entries.value = sequence
            _index.value = sequence.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
            val isPdf = entry.kind == FileKind.DOCUMENT && FileKind.extensionOf(entry.name) == "pdf"
            val isText = !isPdf && TextBookLoader.isPlainText(entry.name) &&
                (entry.kind == FileKind.DOCUMENT || entry.kind == FileKind.OTHER)
            _subject.value = when {
                entry.kind == FileKind.IMAGE -> ViewerSubject.IMAGE
                entry.kind == FileKind.VIDEO -> ViewerSubject.VIDEO
                entry.kind == FileKind.AUDIO -> ViewerSubject.AUDIO
                isPdf -> ViewerSubject.PDF
                isText -> ViewerSubject.TEXT
                else -> ViewerSubject.UNSUPPORTED
            }
            when (_subject.value) {
                ViewerSubject.PDF -> openPdf(entry)
                ViewerSubject.TEXT -> {
                    _ready.value = true
                    loadBook(entry)
                }

                else -> _ready.value = true
            }
        }
    }

    // ---------------------------------------------------------------- 通用

    fun onIndexChanged(target: Int) {
        if (target in _entries.value.indices && target != _index.value) _index.value = target
    }

    fun toggleMode() {
        _mode.value = if (_mode.value == ViewerMode.PAGER) ViewerMode.STRIP else ViewerMode.PAGER
    }

    fun rotate() {
        _rotation.value = (_rotation.value + 90) % 360
    }

    suspend fun renderPdfPage(index: Int, widthPx: Int): Bitmap? = _pdf.value?.page(index, widthPx)

    fun openWithExternal(context: Context) {
        val entry = current ?: return
        val file = runCatching { repo.physicalFile(entry) }.getOrNull() ?: return
        _message.value = com.qoder.pocketvault.util.FileBridge.openWith(context, file, entry)
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun openPdf(entry: VaultEntry) {
        val file = runCatching { repo.physicalFile(entry) }.getOrNull()
        val document = file?.let { runCatching { PdfDocument.open(it) }.getOrNull() }
        _pdf.value = document
        _pdfPageCount.value = document?.pageCount ?: 0
        // 书签是"这份文件"的属性：打开时就该把这一份的书签读出来，
        // 而不是等用户点开列表才发现是空的。
        loadPdfMarks(entryId)
        if (document == null) _message.value = "这份 PDF 打不开，可能已损坏"
        _ready.value = true
    }

    // ---------------------------------------------------------------- 文本

    private fun loadBook(entry: VaultEntry) {
        _bookLoading.value = true
        _bookError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val file = repo.physicalFile(entry)
                TextBookLoader.load(file, encodingOverride)
            }
            _bookLoading.value = false
            result.fold(
                onSuccess = { loaded ->
                    _book.value = loaded
                    if (loaded.chapters.isEmpty() || loaded.totalChars <= 0) {
                        _bookError.value = "这个文件里没有可显示的文字（${loaded.encoding.label} 解码后为空）"
                        return@fold
                    }
                    val saved = runCatching { repo.readingProgress(entryId) }.getOrNull()
                    val mirror = runCatching { graph.prefs.lastReading }.getOrNull()
                        ?.takeIf { it.entryId == entryId }
                    // 进程被划掉时 Room 那条可能没落盘，prefs 的镜像反而更新
                    val fromMirror = mirror != null && (saved == null || mirror.at > saved.updatedAt)
                    val resumeChapter = if (fromMirror) mirror?.chapter else saved?.chapterIndex
                    val resumeParagraph = if (fromMirror) mirror?.paragraph else saved?.paragraphIndex
                    val chapter = (resumeChapter ?: 0).coerceIn(0, loaded.chapters.lastIndex)
                    _chapterIndex.value = chapter
                    showChapter(chapter, restoreParagraph = resumeParagraph)
                    saved?.fontSize?.takeIf { it >= 13f && it <= 32f }?.let { _fontSize.value = it }
                    _bookmarks.value = runCatching { repo.bookmarks(entryId) }.getOrDefault(emptyList())
                },
                onFailure = { error ->
                    _bookError.value = "读取失败：${error.javaClass.simpleName} ${error.message}"
                },
            )
        }
    }

    /** 重新按指定编码解析（识别错了时用户可手动切）。 */
    fun setEncoding(encoding: TextEncoding) {
        if (encoding == encodingOverride) return
        encodingOverride = encoding
        _book.value?.file?.let { file ->
            _bookLoading.value = true
            viewModelScope.launch(Dispatchers.IO) {
                val chapter = _chapterIndex.value
                val paragraph = _paragraphIndex.value
                val reloaded = runCatching { TextBookLoader.load(file, encoding) }.getOrNull()
                _bookLoading.value = false
                if (reloaded == null) {
                    _bookError.value = "换用 ${encoding.label} 仍然读不出内容"
                } else {
                    _book.value = reloaded
                    _chapterIndex.value = chapter.coerceIn(0, reloaded.chapters.lastIndex)
                    showChapter(_chapterIndex.value, restoreParagraph = paragraph)
                    _message.value = "已改用 ${encoding.label} 重新解析"
                }
            }
        }
    }

    private fun showChapter(index: Int, restoreParagraph: Int?) {
        val book = _book.value ?: return
        val chapter = book.chapters.getOrNull(index) ?: return
        val wanted = (restoreParagraph ?: 0).coerceAtLeast(0)
        // 先清空：否则新章加载的那一帧还在显示上一章的内容
        _paragraphs.value = emptyList()
        // 先把落点占住：加载期间列表是空的，LazyColumn 报回来的第 0 项不该当成阅读位置
        _resumeParagraph.value = wanted
        // 连着切章时只让最后一次加载写结果，不然旧章的段落会盖上来。
        // 光靠 cancel() 不够：TextBook.paragraphsOf 里全是阻塞的读盘 + 解码，
        // 没有挂起点，被 cancel 的旧任务照样会跑完并写回 —— 所以额外用令牌比对。
        chapterJob?.cancel()
        val token = ++chapterToken
        chapterJob = viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { book.paragraphsOf(index) }.getOrDefault(emptyList())
            if (token != chapterToken || !isActive) return@launch
            _paragraphs.value = list
            val target = wanted.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            _paragraphIndex.value = target
            _resumeParagraph.value = target
        }
        // 兜底：这个契约不该完全依赖 UI 配合。整章没有段落时 UI 的 scrollToItem 落不到位、
        // 不会来 consumeResume；但章节还在加载时不能抢，否则空列表报回来的第 0 项会冲掉进度。
        resumeGuard?.cancel()
        resumeGuard = viewModelScope.launch {
            delay(RESUME_GUARD_MS)
            if (_resumeParagraph.value >= 0 && _paragraphs.value.isEmpty()) _resumeParagraph.value = -1
        }
    }

    fun selectChapter(target: Int) {
        val book = _book.value ?: return
        val chapter = book.chapters.getOrNull(target) ?: return
        if (_chapterIndex.value == target) return
        _chapterIndex.value = target
        _paragraphIndex.value = 0
        showChapter(chapter.order, restoreParagraph = 0)
        persistPosition()
    }

    /**
     * 精确跳到「某章某段」。不走 selectChapter 的同章早退，所以点当前章的搜索结果、
     * 连着两次点同一个书签，都一定会有动作。
     */
    fun jumpTo(chapter: Int, paragraph: Int) {
        val book = _book.value ?: return
        val target = chapter.coerceIn(0, book.chapters.lastIndex)
        val paragraphTarget = paragraph.coerceAtLeast(0)
        _chapterIndex.value = target
        _paragraphIndex.value = paragraphTarget
        showChapter(target, restoreParagraph = paragraphTarget)
        persistPosition()
    }

    fun stepChapter(delta: Int) {
        val book = _book.value ?: return
        selectChapter((_chapterIndex.value + delta).coerceIn(0, book.chapters.lastIndex))
    }

    /**
     * 列表滚动回调：只记录章内段落序号，变化时才写库。
     * 跳转还没落位（_resumeParagraph 未复位）时不收：那之前 LazyColumn 报回来的
     * 还是清空列表后的第 0 项，会把刚恢复的进度冲掉。
     */
    fun onParagraphRead(withinChapter: Int) {
        if (_resumeParagraph.value >= 0) return
        if (withinChapter == _paragraphIndex.value) return
        noteBlocksAdvanced(_paragraphIndex.value, withinChapter)
        _paragraphIndex.value = withinChapter
        persistPosition()
    }

    fun setFontSize(size: Float) {
        val coerced = size.coerceIn(13f, 32f)
        if (coerced == _fontSize.value) return
        _fontSize.value = coerced
        graph.prefs.readingFontSize = coerced
        persistPosition()
    }

    fun setLineSpacing(value: Float) {
        val coerced = value.coerceIn(1.3f, 2.4f)
        if (coerced == _lineSpacing.value) return
        _lineSpacing.value = coerced
        graph.prefs.readingLineSpacing = coerced
    }

    fun setTheme(index: Int) {
        val coerced = index.coerceIn(0, ReaderTheme.values().lastIndex)
        _themeIndex.value = coerced
        graph.prefs.readingTheme = coerced
    }

    fun setMargin(dp: Int) {
        val coerced = dp.coerceIn(8, 40)
        _margin.value = coerced
        graph.prefs.readingMargin = coerced
    }

    fun toggleBookmark() {
        val book = _book.value ?: return
        val chapter = book.chapters.getOrNull(_chapterIndex.value) ?: return
        val paragraph = _paragraphIndex.value
        viewModelScope.launch(Dispatchers.IO) {
            val existing = runCatching { repo.bookmarks(entryId) }.getOrDefault(emptyList())
            val hit = existing.firstOrNull { it.chapterIndex == chapter.order && it.paragraphIndex == paragraph }
            if (hit == null) {
                val preview = _paragraphs.value.getOrNull(paragraph)?.take(24).orEmpty()
                runCatching { repo.addBookmark(entryId, chapter.order, paragraph, preview.ifBlank { chapter.title }) }
                _message.value = "已加书签：${chapter.title} 第 ${paragraph + 1} 段"
            } else {
                runCatching { repo.removeBookmark(entryId, chapter.order, paragraph) }
                _message.value = "已移除该处书签"
            }
            _bookmarks.value = runCatching { repo.bookmarks(entryId) }.getOrDefault(emptyList())
        }
    }

    /** 书签列表里的删除按钮：按书签自身位置删，不受当前阅读位置影响。 */
    fun removeBookmarkAt(bookmark: ReaderBookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.removeBookmark(entryId, bookmark.chapterIndex, bookmark.paragraphIndex) }
            _bookmarks.value = runCatching { repo.bookmarks(entryId) }.getOrDefault(emptyList())
        }
    }

    fun jumpToBookmark(bookmark: ReaderBookmark) {
        jumpTo(bookmark.chapterIndex, bookmark.paragraphIndex)
    }

    /**
     * 全文搜索：防抖 250ms，新查询取消旧查询；写回前确认查询没被改过。
     * 少了这几步，快速输入时旧结果会盖掉新结果，界面来回闪。
     */
    fun search(text: String) {
        _searchQuery.value = text
        searchJob?.cancel()
        val book = _book.value
        if (book == null || text.isBlank()) {
            _searchBusy.value = false
            _searchResults.value = emptyList()
            return
        }
        _searchBusy.value = true
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            val found = runCatching { book.search(text) }.getOrDefault(emptyList())
            // 只写回仍然是最新查询的结果，否则旧结果会盖掉新结果
            if (isActive && _searchQuery.value == text) {
                _searchResults.value = found
                _searchBusy.value = false
            }
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        searchJob?.cancel()
        _searchBusy.value = false
        _searchResults.value = emptyList()
    }

    private fun persistPosition() {
        persistJob?.cancel()
        if (_book.value == null) return
        mirrorPosition()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            runCatching {
                repo.saveReadingProgress(entryId, _chapterIndex.value, _paragraphIndex.value, _fontSize.value)
            }
        }
    }

    /** prefs 写是内存里改一份、磁盘异步，主线程调用没问题；它比 Room 那条更早生效。 */
    private fun mirrorPosition() {
        graph.prefs.lastReading = LastReading(entryId, _chapterIndex.value, _paragraphIndex.value, System.currentTimeMillis())
    }

    /**
     * 立刻落盘，不给防抖留余地：退出阅读时全靠它。
     * 必须用 AppGraph 的进程级作用域——viewModelScope 在 onCleared 时已经取消了。
     * 没成功加载出书就不写：那时章/段落都是 0，会把库里原有的进度冲掉。
     */
    fun flushProgress() {
        persistJob?.cancel()
        if (_book.value == null) return
        mirrorPosition()
        val chapter = _chapterIndex.value
        val paragraph = _paragraphIndex.value
        val size = _fontSize.value
        graph.ioScope.launch {
            runCatching { repo.saveReadingProgress(entryId, chapter, paragraph, size) }
        }
    }

    /** UI 滚到目标段落之后调用；不复位的话，第二次跳同一段会被 StateFlow 去重吞掉。 */
    fun consumeResume() {
        if (_resumeParagraph.value != -1) _resumeParagraph.value = -1
    }

    override fun onCleared() {
        stopReadingTimer()
        flushProgress()
        val document = _pdf.value
        _pdf.value = null
        // onCleared 里 viewModelScope 已经取消了，只能走进程级作用域；
        // close 会等渲染锁后面那一页画完，不然和 native 的 close 打架
        if (document != null) graph.ioScope.launch { runCatching { document.closeAsync() } }
    }
}

/** 落点占位的最长存活时间：UI 没来消费也得放行。 */
private const val RESUME_GUARD_MS = 800L

/** 阅读计时一格多少秒。 */
private const val STAT_TICK_SECONDS = 10
