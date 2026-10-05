package com.qoder.pocketvault.util

import com.qoder.pocketvault.core.FileKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 中文 TXT 实际会遇到的几种编码；Big5/UTF-16 也一并支持，识别不了还能手动切。 */
enum class TextEncoding(val label: String, val charsetName: String) {
    UTF_8("UTF-8", "UTF-8"),
    GB18030("GB18030", "GB18030"),
    BIG5("Big5", "Big5"),
    UTF_16LE("UTF-16LE", "UTF-16LE"),
    UTF_16BE("UTF-16BE", "UTF-16BE"),
    ;

    val charset: java.nio.charset.Charset get() = java.nio.charset.Charset.forName(charsetName)
}

/**
 * 编码判定：先看 BOM，再严格试 UTF-8，然后统计解码结果里 U+FFFD 的占比。
 * 占比超阈值就认定不是 UTF-8，改试 GB18030；仍不理想再退到 Big5。
 * 判定结果只是默认值，界面上永远允许用户手动改。
 */
object TextEncodingDetector {

    private const val SAMPLE_BYTES = 512 * 1024
    private const val REPLACEMENT_RATIO_LIMIT = 0.0015f

    /** UTF-8 一个汉字最多 4 字节：样本末尾被切断的这点字节不能算非法。 */
    private const val TAIL_SLACK_BYTES = 4

    fun detect(file: File): TextEncoding {
        val bytes = readHead(file, SAMPLE_BYTES)
        if (bytes.isEmpty()) return TextEncoding.UTF_8
        return when {
            startsWith(bytes, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> TextEncoding.UTF_8
            startsWith(bytes, byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> TextEncoding.UTF_16LE
            startsWith(bytes, byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> TextEncoding.UTF_16BE
            else -> byHeuristic(bytes)
        }
    }

    /** InputStream.read 一次不保证读满，得自己补读到够数或读到 EOF。 */
    private fun readHead(file: File, size: Int): ByteArray {
        val buffer = ByteArrayOutputStream(size)
        val chunk = ByteArray(64 * 1024)
        runCatching {
            FileInputStream(file).use { stream ->
                var total = 0
                while (total < size) {
                    val count = stream.read(chunk, 0, minOf(chunk.size, size - total))
                    if (count <= 0) break
                    buffer.write(chunk, 0, count)
                    total += count
                }
            }
        }
        return buffer.toByteArray()
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun byHeuristic(bytes: ByteArray): TextEncoding {
        if (decodesAsUtf8(bytes)) return TextEncoding.UTF_8
        // UTF-8 确实解不动：按替换字符比例在 GB18030 / Big5 之间挑
        val gb = decodeLenient(bytes, TextEncoding.GB18030.charset)
        val big5 = decodeLenient(bytes, TextEncoding.BIG5.charset)
        return when {
            replacementRatio(gb) <= REPLACEMENT_RATIO_LIMIT -> TextEncoding.GB18030
            replacementRatio(big5) < replacementRatio(gb) -> TextEncoding.BIG5
            else -> TextEncoding.GB18030
        }
    }

    /**
     * 严格 UTF-8 判定，但容忍样本尾部被切断的那个字符。
     *
     * 采样是按字节切的（512K，切在三字节汉字中间是常态），旧写法 endOfInput=true
     * 会在这里抛 MalformedInputException，于是一本干净的 UTF-8 长小说全部误判成
     * GB18030 —— GB18030 几乎能解任意字节，替换字符比例也过不了阈值。
     * 改 endOfInput=false 后，未完成的多字节序列返回 UNDERFLOW 而不是 MALFORMED。
     * 再兜一层：非法只发生在最后 4 字节内，同样认定 UTF-8。
     */
    private fun decodesAsUtf8(bytes: ByteArray): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val out = CharBuffer.allocate(bytes.size + 1)
        val result = runCatching { decoder.decode(input, out, false) }.getOrNull() ?: return false
        if (!result.isError) return true
        return result.isMalformed && input.position() >= bytes.size - TAIL_SLACK_BYTES
    }

    private fun decodeLenient(bytes: ByteArray, charset: java.nio.charset.Charset): String =
        runCatching { String(bytes, charset) }.getOrElse { "" }

    private fun replacementRatio(text: String): Float {
        if (text.isEmpty()) return 1f
        var bad = 0
        for (c in text) if (c == '\uFFFD') bad++
        return bad.toFloat() / text.length
    }
}

data class TextChapter(
    val title: String,
    val charStart: Int,
    val charEnd: Int,
    val byteStart: Long,
    val byteEnd: Long,
    val order: Int,
) {
    val length: Int get() = (charEnd - charStart).coerceAtLeast(0)
}

/** 对外入口：判断是否纯文本、按编码加载成索引。 */
object TextBookLoader {

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "log", "csv", "json", "xml", "yaml", "yml",
        "ini", "conf", "sql", "srt", "ass",
    )

    fun isPlainText(name: String): Boolean = FileKind.extensionOf(name) in TEXT_EXTENSIONS

    fun load(file: File, encoding: TextEncoding? = null): TextBook = TextBook.load(file, encoding)
}

/**
 * 一本纯文本的索引 + 取段能力。
 *
 * 打开时只扫一遍建索引（每章记字符区间 + 字节区间），正文不驻留内存；
 * 读某一章时按字节区间 seek 过去，只解码那一段。章节边界一定落在行首，
 * 而 UTF-8/GB18030/Big5 的 0x0A 不可能是多字节字符的一部分，UTF-16 按码元切，
 * 所以截出来的字节区间必然是完整字符。几百 MB 的书切章、搜索都不会退化成 O(N²)。
 */
class TextBook private constructor(
    val file: File,
    val title: String,
    val encoding: TextEncoding,
    val chapters: List<TextChapter>,
    val totalChars: Int,
    val truncated: Boolean,
) {

    fun chapterAt(index: Int): TextChapter? = chapters.getOrNull(index)

    /** 取某一章的段落列表：去掉空行，超长段按句末补切。 */
    fun paragraphsOf(index: Int): List<String> {
        val chapter = chapterAt(index) ?: return emptyList()
        return splitParagraphs(readRange(chapter.byteStart, chapter.byteEnd))
    }

    /**
     * 全文搜索。每章最多留 [MAX_HITS_PER_CHAPTER] 条，超出部分折成摘要里的
     * 「本章命中 M 处」——否则高频词的前两章就能把 limit 吃满，后面所有章节
     * 一条都出不来。段落循环里也要查 limit。
     */
    suspend fun search(query: String, limit: Int = 80): List<SearchHit> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val needle = query.trim()
        val hits = ArrayList<SearchHit>()
        for (chapter in chapters) {
            if (hits.size >= limit) break
            // 纯 CPU 循环，不给取消点的话，用户退了阅读界面还在后台扫全书
            coroutineContext.ensureActive()
            val paragraphs = paragraphsOf(chapter.order)
            var matched = 0
            var lastOfChapter = -1
            for (position in paragraphs.indices) {
                if (hits.size >= limit) break
                val line = paragraphs[position]
                if (!line.contains(needle, ignoreCase = true)) continue
                matched++
                // 超出每章额度就只计数不占位，额度留给后面的章节
                if (matched > MAX_HITS_PER_CHAPTER) continue
                lastOfChapter = hits.size
                hits += SearchHit(chapter.order, position, snippet(line, needle))
            }
            if (matched > MAX_HITS_PER_CHAPTER && lastOfChapter >= 0) {
                val hit = hits[lastOfChapter]
                hits[lastOfChapter] = hit.copy(preview = "${hit.preview} · 本章命中 $matched 处")
            }
        }
        return hits
    }

    private fun snippet(line: String, needle: String): String {
        val index = line.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
        val from = (index - 18).coerceAtLeast(0)
        val to = (index + needle.length + 42).coerceAtMost(line.length)
        return line.substring(from, to)
    }

    private fun readRange(byteStart: Long, byteEnd: Long): String {
        val end = byteEnd.coerceAtMost(file.length())
        val size = (end - byteStart).coerceAtLeast(0L).coerceAtMost(MAX_CHAPTER_BYTES)
        if (size == 0L) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { handle ->
                handle.seek(byteStart)
                val bytes = ByteArray(size.toInt())
                handle.readFully(bytes)
                String(bytes, encoding.charset)
            }
        }.getOrDefault("")
    }

    private fun splitParagraphs(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(128)
        var cursor = 0
        while (true) {
            val newline = text.indexOf('\n', cursor)
            val lineEnd = if (newline < 0) text.length else newline
            val paragraph = text.substring(cursor, lineEnd).trim()
            if (paragraph.isNotEmpty()) appendParagraph(paragraph, out)
            if (newline < 0) break
            cursor = newline + 1
        }
        return out
    }

    /** 整章没有换行的 TXT，一个字塞进一个 Text 会让 Compose 排版掉帧甚至 ANR。 */
    private fun appendParagraph(paragraph: String, out: MutableList<String>) {
        if (paragraph.length <= MAX_PARAGRAPH_CHARS) {
            out += paragraph
            return
        }
        var start = 0
        while (start < paragraph.length) {
            val hardEnd = minOf(start + MAX_PARAGRAPH_CHARS, paragraph.length)
            val end = if (hardEnd < paragraph.length) {
                stopAfter(paragraph, hardEnd, start + MIN_PARAGRAPH_CHARS)
            } else {
                hardEnd
            }
            out += paragraph.substring(start, end)
            start = end
        }
    }

    /** 往回找最近的句末标点，找不到就硬切；接缝仍是连续正文，看不出来。 */
    private fun stopAfter(text: String, from: Int, floor: Int): Int {
        var index = from - 1
        while (index > floor) {
            if (text[index] in SENTENCE_STOPS) return index + 1
            index--
        }
        return from
    }

    data class SearchHit(val chapterIndex: Int, val paragraphIndex: Int, val preview: String)

    companion object {
        private const val MAX_CHAPTER_BYTES = 8L shl 20
        private const val MAX_PARAGRAPH_CHARS = 1_400
        private const val MIN_PARAGRAPH_CHARS = 600
        private const val MAX_HITS_PER_CHAPTER = 3
        private const val TITLE_MAX_CHARS = 60

        private val SENTENCE_STOPS = charArrayOf(
            '。', '！', '？', '；', '：', '”', '’', '』', '】', '）',
            '.', '!', '?', ';', '"', '\'',
        )

        /** 章节标题优先级：中文常见 → 卷/部 → 英文 → 序号开头 → 纯数字行。 */
        private val CHAPTER_PATTERNS = listOf(
            Regex("^\\s*第\\s*[0-9〇零一二两三四五六七八九十百千]{1,8}\\s*[章回节篇话卷]{1,2}[\\s\\S]{0,40}$"),
            Regex("^\\s*(序\\s*[章言]|楔\\s*子|尾\\s*声|后\\s*记|前\\s*言|附\\s*言|番\\s*外[\\s\\S]{0,20})$"),
            Regex("^\\s*chapter\\s*[0-9ivxIVX]+[\\s\\S]{0,40}$", RegexOption.IGNORE_CASE),
            Regex("^\\s*[0-9]{1,4}\\s*[.、．]\\s*\\S[\\s\\S]{0,40}$"),
            Regex("^\\s*[0-9]{1,5}\\s*$"),
        )

        fun load(file: File, encodingOverride: TextEncoding? = null): TextBook {
            val encoding = encodingOverride ?: TextEncodingDetector.detect(file)
            val index = TextIndexScanner(file, encoding).run()
            return TextBook(
                file = file,
                title = file.name.substringBeforeLast('.'),
                encoding = encoding,
                chapters = index.chapters,
                totalChars = index.totalChars,
                truncated = index.truncated,
            )
        }

        internal fun isChapterTitle(line: String): Boolean =
            line.length <= TITLE_MAX_CHARS && CHAPTER_PATTERNS.any { it.matches(line) }
    }
}

/** 单遍扫描的结果：章节索引 + 全书字符数 + 是否中途读挂了。 */
private class IndexOutcome(
    val chapters: List<TextChapter>,
    val totalChars: Int,
    val truncated: Boolean,
)

/**
 * 扫一遍文件建章节索引，只记行首的（字节位置，字符位置），正文扫过就丢。
 *
 * 换行在原始字节上匹配：UTF-8/GB18030/Big5 里 0x0A 不会出现在多字节字符内部，
 * UTF-16 则匹配整个码元。UTF-16 每块只处理成双的字节，落单的那个下一轮重读，
 * 省掉跨块的缓存状态。
 */
private class TextIndexScanner(
    private val file: File,
    private val encoding: TextEncoding,
) {

    private val unit =
        if (encoding == TextEncoding.UTF_16LE || encoding == TextEncoding.UTF_16BE) 2 else 1

    private val fileSize = file.length()

    private val parts = ArrayList<TextChapter>(256)
    private val line = ByteArrayOutputStream(64 * 1024)

    private var charPos = 0
    private var lineCharStart = 0
    private var lineByteStart = 0L

    /** null = 这一章还没标题，落定前会补成「开篇」或「第 N 部分」。 */
    private var chapterTitle: String? = null
    private var chapterOrigin: String? = null
    private var chapterCharStart = 0
    private var chapterByteStart = 0L
    private var continuation = 0
    private var hadMarker = false

    fun run(): IndexOutcome {
        val length = fileSize
        if (length <= 0L) return IndexOutcome(listOf(TextChapter("正文", 0, 0, 0L, 0L, 0)), 0, false)
        var truncated = false
        try {
            RandomAccessFile(file, "r").use { handle ->
                val head = ByteArray(minOf(4L, length).toInt())
                handle.seek(0)
                handle.readFully(head)
                var base = bomLength(head).toLong()
                // BOM 不属于任何一行：章起点也得跟着跳过，否则第一章会把 BOM 字节当正文
                lineByteStart = base
                chapterByteStart = base

                val chunk = ByteArray(CHUNK_BYTES)
                while (base < length) {
                    val want = minOf(CHUNK_BYTES.toLong(), length - base).toInt()
                    handle.seek(base)
                    handle.readFully(chunk, 0, want)
                    val usable = if (unit == 2) want - (want % unit) else want
                    if (usable <= 0) break
                    var i = 0
                    while (i < usable) {
                        if (isBreak(chunk, i)) {
                            val next = base + i + unit
                            closeLine(next, realNewline = true)
                            i += unit
                            lineByteStart = next
                        } else {
                            line.write(chunk, i, unit)
                            i += unit
                        }
                    }
                    base += usable
                    // 一整本没有换行时，行缓冲不能无限长
                    if (line.size() >= MAX_LINE_BYTES) cutOversizedLine(base)
                }
                if (line.size() > 0) closeLine(length, realNewline = false)
                closeChapter(length, charPos)
            }
        } catch (e: Exception) {
            truncated = true
        }
        return IndexOutcome(chapters(), charPos, truncated)
    }

    private fun bomLength(head: ByteArray): Int {
        if (head.size < 2) return 0
        val utf8Bom = encoding == TextEncoding.UTF_8 && head.size >= 3 &&
            head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        if (utf8Bom) return 3
        val utf16Bom = encoding != TextEncoding.UTF_8 && encoding != TextEncoding.GB18030 &&
            encoding != TextEncoding.BIG5 &&
            ((head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) ||
                (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()))
        return if (utf16Bom) 2 else 0
    }

    private fun isBreak(chunk: ByteArray, i: Int): Boolean = when {
        unit == 1 -> chunk[i] == LINE_FEED
        encoding == TextEncoding.UTF_16LE -> chunk[i] == LINE_FEED && chunk[i + 1] == ZERO
        else -> chunk[i] == ZERO && chunk[i + 1] == LINE_FEED
    }

    /** [nextLineByteStart] 是下一行的第一个字节；最后一行传文件尾。 */
    private fun closeLine(nextLineByteStart: Long, realNewline: Boolean) {
        val bytes = line.toByteArray()
        line.reset()
        // 保留 \r：它照样占一个字符，字符偏移才对得上真实解码结果，显示时靠 trim 去掉
        val text = String(bytes, encoding.charset)
        val byteStart = lineByteStart
        val charStart = lineCharStart
        val newline = if (realNewline && nextLineByteStart < fileSize) 1 else 0
        charPos += text.length + newline
        lineCharStart = charPos
        onLine(text.trim(), byteStart, charStart)
    }

    /** 超长行强制分段：UTF-8 退回完整字符边界，避免切出一个 U+FFFD。 */
    private fun cutOversizedLine(chunkEnd: Long) {
        val bytes = line.toByteArray()
        val cut = safeCut(bytes)
        val tail = bytes.size - cut
        val text = String(bytes, 0, cut, encoding.charset)
        val byteStart = lineByteStart
        val charStart = lineCharStart
        charPos += text.length
        lineCharStart = charPos
        line.reset()
        if (tail > 0) line.write(bytes, cut, tail)
        lineByteStart = chunkEnd - tail
        onLine(text.trim(), byteStart, charStart)
    }

    private fun safeCut(bytes: ByteArray): Int {
        if (encoding != TextEncoding.UTF_8) return bytes.size
        var cut = bytes.size
        while (cut > bytes.size - 4 && cut > 0 && (bytes[cut - 1].toInt() and 0xFF) >= 0x80) cut--
        return cut
    }

    private fun onLine(trimmed: String, byteStart: Long, charStart: Int) {
        if (trimmed.isEmpty()) return
        if (TextBook.isChapterTitle(trimmed)) {
            hadMarker = true
            closeChapter(byteStart, charStart)
            chapterTitle = trimmed
            chapterOrigin = trimmed
            continuation = 0
        } else if (charStart - chapterCharStart >= MAX_CHAPTER_CHARS) {
            closeChapter(byteStart, charStart)
            continuation++
            val origin = chapterOrigin
            chapterTitle = if (origin == null) null else "$origin · $continuation"
        } else {
            return
        }
        chapterCharStart = charStart
        chapterByteStart = byteStart
    }

    private fun closeChapter(byteEnd: Long, charEnd: Int) {
        if (byteEnd <= chapterByteStart) return
        val fallback = if (parts.isEmpty()) "开篇" else "第 ${parts.size + 1} 部分"
        parts += TextChapter(
            title = chapterTitle ?: fallback,
            charStart = chapterCharStart,
            charEnd = charEnd,
            byteStart = chapterByteStart,
            byteEnd = byteEnd,
            order = parts.size,
        )
        chapterCharStart = charEnd
        chapterByteStart = byteEnd
    }

    private fun chapters(): List<TextChapter> {
        if (parts.isEmpty()) {
            return listOf(TextChapter("正文", chapterCharStart, maxOf(charPos, 1), chapterByteStart, fileSize, 0))
        }
        // 一个标题都没认出来时，统一叫「第 N 部分」比「开篇 + 第 N 部分」顺
        if (hadMarker) return parts
        return parts.mapIndexed { index, chapter -> chapter.copy(title = "第 ${index + 1} 部分") }
    }

    companion object {
        private const val CHUNK_BYTES = 256 * 1024
        private const val MAX_LINE_BYTES = 2 * 1024 * 1024

        /** 与 1.8 的分段大小保持一致：认不出标题时按此切「第 N 部分」，老进度/书签的章号才不会整体错位。 */
        private const val MAX_CHAPTER_CHARS = 180_000
        private val LINE_FEED = 0x0A.toByte()
        private val ZERO = 0x00.toByte()
    }
}
