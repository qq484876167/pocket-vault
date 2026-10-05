import Foundation

/// 纯文本 / 小说的编码与取段能力。
///
/// 这是 Android 版 `util/TextBook.kt` 的对应实现，行为刻意保持一致：
/// 打开时只扫一遍建索引（每章记字符区间 + 字节区间），正文不驻留内存；
/// 读某一章时按字节区间 seek 过去，只解码那一段。章节边界恒在行首，而
/// UTF-8/GB18030/Big5 的 0x0A 不会出现在多字节字符内部，UTF-16 按 2 字节码元匹配，
/// 所以截出来的字节区间必然是完整字符。
enum TextEncoding: String, CaseIterable, Identifiable {
    case utf8 = "UTF-8"
    case gb18030 = "GB18030"
    case big5 = "Big5"
    case utf16LE = "UTF-16LE"
    case utf16BE = "UTF-16BE"

    var id: String { rawValue }
    var label: String { rawValue }

    /// GB18030 / Big5 经 CoreFoundation 取 NSStringEncoding。
    var nsEncoding: String.Encoding {
        switch self {
        case .utf8: return .utf8
        case .utf16LE: return .utf16LittleEndian
        case .utf16BE: return .utf16BigEndian
        case .gb18030: return TextEncoding.fromCoreFoundation(.GB_18030_2000)
        case .big5: return TextEncoding.fromCoreFoundation(.Big5)
        }
    }

    private static func fromCoreFoundation(_ id: CFStringEncodings) -> String.Encoding {
        String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(id.rawValue)))
    }

    /// 一个解码单元占几个字节；UTF-16 必须按码元对齐，不能按字节切。
    var unit: Int { (self == .utf16LE || self == .utf16BE) ? 2 : 1 }
}

struct TextChapter: Equatable {
    let title: String
    let charStart: Int
    let charEnd: Int
    let byteStart: UInt64
    let byteEnd: UInt64
    let order: Int

    var length: Int { max(charEnd - charStart, 0) }

    func renaming(to newTitle: String) -> TextChapter {
        TextChapter(title: newTitle, charStart: charStart, charEnd: charEnd,
                    byteStart: byteStart, byteEnd: byteEnd, order: order)
    }
}

/// UTF-8 结构校验结果。不能只看"能不能解出字符串"，必须知道非法出现在哪里，
/// 才能区分"样本末尾被切断"和"这压根不是 UTF-8"。
enum Utf8Verdict: Equatable {
    case valid
    case truncatedTail
    case invalid(at: Int)
}

enum Utf8Validator {
    /// 严格校验：拒绝过长编码、代理区码点、超过 U+10FFFF、游离续字节。
    static func validate(_ bytes: [UInt8]) -> Utf8Verdict {
        var index = 0
        while index < bytes.count {
            let lead = bytes[index]
            if lead < 0x80 {
                index += 1
                continue
            }
            guard let spec = sequence(for: lead) else { return .invalid(at: index) }
            if index + spec.length > bytes.count { return .truncatedTail }
            var offset = 1
            while offset < spec.length {
                let byte = bytes[index + offset]
                let low = offset == 1 ? spec.secondLow : 0x80
                let high = offset == 1 ? spec.secondHigh : 0xBF
                if byte < low || byte > high { return .invalid(at: index + offset) }
                offset += 1
            }
            index += spec.length
        }
        return .valid
    }

    private struct Sequence {
        let length: Int
        let secondLow: UInt8
        let secondHigh: UInt8
    }

    /// 首字节决定长度与第二字节的合法区间——区间收窄正是排除过长编码和代理区的手段。
    private static func sequence(for lead: UInt8) -> Sequence? {
        switch lead {
        case 0x80...0xBF: return nil
        case 0xC0, 0xC1: return nil
        case 0xC2...0xDF: return Sequence(length: 2, secondLow: 0x80, secondHigh: 0xBF)
        case 0xE0: return Sequence(length: 3, secondLow: 0xA0, secondHigh: 0xBF)
        case 0xE1...0xEC, 0xEE...0xEF: return Sequence(length: 3, secondLow: 0x80, secondHigh: 0xBF)
        case 0xED: return Sequence(length: 3, secondLow: 0x80, secondHigh: 0x9F)
        case 0xF0: return Sequence(length: 4, secondLow: 0x90, secondHigh: 0xBF)
        case 0xF1...0xF3: return Sequence(length: 4, secondLow: 0x80, secondHigh: 0xBF)
        case 0xF4: return Sequence(length: 4, secondLow: 0x80, secondHigh: 0x8F)
        default: return nil
        }
    }
}

/// 编码判定。结果只是默认值，界面上永远允许用户手动改。
enum TextEncodingDetector {

    private static let sampleBytes = 512 * 1024
    /// UTF-8 一个汉字最多 4 字节：非法只出现在末尾 4 字节内仍按 UTF-8 处理。
    private static let tailSlack = 4
    /// "像不像中文正文"的下限。Apple 的解码是严格成功/失败，拿不到 Android 上
    /// 那种"解出来但一堆 U+FFFD"的中间态，所以改用 CJK 占比来区分 GB18030 与 Big5。
    private static let cjkRatioFloor = 0.5

    static func detect(_ url: URL) -> TextEncoding {
        guard let bytes = readHead(url, sampleBytes), !bytes.isEmpty else { return .utf8 }

        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { return .utf8 }
        if bytes.starts(with: [0xFF, 0xFE]) { return .utf16LE }
        if bytes.starts(with: [0xFE, 0xFF]) { return .utf16BE }

        switch Utf8Validator.validate(bytes) {
        case .valid, .truncatedTail: return .utf8
        case .invalid(let at):
            if at >= bytes.count - tailSlack { return .utf8 }
        }

        let gb = cjkRatio(bytes, .gb18030)
        let big5 = cjkRatio(bytes, .big5)
        if gb == nil && big5 == nil { return .gb18030 }
        if let big5, gb == nil, big5 >= cjkRatioFloor { return .big5 }
        if let gb, gb >= cjkRatioFloor { return .gb18030 }
        return (gb ?? -1) >= (big5 ?? -1) ? .gb18030 : .big5
    }

    /// nil = 严格解码失败；否则给出 CJK 与中文标点合计占比。
    private static func cjkRatio(_ bytes: [UInt8], _ encoding: TextEncoding) -> Double? {
        guard let text = String(data: Data(bytes), encoding: encoding.nsEncoding) else { return nil }
        let sample = Array(text.prefix(20_000))
        guard !sample.isEmpty else { return nil }
        let hits = sample.filter { isHan($0) || isChinesePunctuation($0) }.count
        return Double(hits) / Double(sample.count)
    }

    private static func isHan(_ character: Character) -> Bool {
        guard character.unicodeScalars.count == 1, let scalar = character.unicodeScalars.first else { return false }
        return (0x4E00...0x9FFF).contains(scalar.value)
            || (0x3400...0x4DBF).contains(scalar.value)
            || (0xF900...0xFAFF).contains(scalar.value)
    }

    private static func isChinesePunctuation(_ character: Character) -> Bool {
        guard character.unicodeScalars.count == 1, let scalar = character.unicodeScalars.first else { return false }
        return (0x3000...0x303F).contains(scalar.value) || (0xFF00...0xFFEF).contains(scalar.value)
    }

    private static func readHead(_ url: URL, _ size: Int) -> [UInt8]? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var collected = Data()
        let chunk = 64 * 1024
        while collected.count < size {
            guard let piece = try? handle.read(upToCount: min(chunk, size - collected.count)),
                  !piece.isEmpty else { break }
            collected.append(piece)
        }
        return [UInt8](collected)
    }
}

/// 一本纯文本的索引 + 取段能力。
final class TextBook {

    let url: URL
    let title: String
    let encoding: TextEncoding
    let chapters: [TextChapter]
    let totalChars: Int
    let truncated: Bool

    struct SearchHit: Equatable {
        let chapterIndex: Int
        let paragraphIndex: Int
        let preview: String
    }

    private init(url: URL, title: String, encoding: TextEncoding, chapters: [TextChapter],
                 totalChars: Int, truncated: Bool) {
        self.url = url
        self.title = title
        self.encoding = encoding
        self.chapters = chapters
        self.totalChars = totalChars
        self.truncated = truncated
    }

    static func load(_ url: URL, encodingOverride: TextEncoding? = nil) -> TextBook {
        let encoding = encodingOverride ?? TextEncodingDetector.detect(url)
        let scan = TextIndexScanner(url: url, encoding: encoding).run()
        return TextBook(
            url: url,
            title: url.deletingPathExtension().lastPathComponent,
            encoding: encoding,
            chapters: scan.chapters,
            totalChars: scan.totalChars,
            truncated: scan.truncated
        )
    }

    static func isPlainText(_ name: String) -> Bool {
        TextBookConstants.textExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    func chapter(at index: Int) -> TextChapter? {
        chapters.indices.contains(index) ? chapters[index] : nil
    }

    /// 取某一章的段落列表：去掉空行，超长段按句末补切。
    func paragraphs(of index: Int) -> [String] {
        guard let chapter = chapter(at: index) else { return [] }
        return TextParagraphs.split(readRange(from: chapter.byteStart, to: chapter.byteEnd))
    }

    /// 全文搜索。每章最多留 `maxHitsPerChapter` 条，超出折成"本章命中 M 处"，
    /// 段落循环里也要查总额度——否则高频词把额度吃满前两章，后面的章节一条都出不来。
    func search(_ query: String, limit: Int = 80) async throws -> [SearchHit] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty, limit > 0 else { return [] }
        var hits: [SearchHit] = []
        for chapter in chapters {
            if hits.count >= limit { break }
            // 纯 CPU 循环，不给取消点的话用户退了阅读界面还在后台扫全书
            try Task.checkCancellation()
            let paragraphs = paragraphs(of: chapter.order)
            var matched = 0
            var lastOfChapter = -1
            for position in paragraphs.indices {
                if hits.count >= limit { break }
                guard contains(paragraphs[position], needle) else { continue }
                matched += 1
                if matched > TextBookConstants.maxHitsPerChapter { continue }
                lastOfChapter = hits.count
                hits.append(SearchHit(chapterIndex: chapter.order, paragraphIndex: position,
                                      preview: snippet(paragraphs[position], needle)))
            }
            if matched > TextBookConstants.maxHitsPerChapter, lastOfChapter >= 0 {
                let hit = hits[lastOfChapter]
                hits[lastOfChapter] = SearchHit(chapterIndex: hit.chapterIndex, paragraphIndex: hit.paragraphIndex,
                                                preview: "\(hit.preview) · 本章命中 \(matched) 处")
            }
        }
        return hits
    }

    // MARK: - 内部

    private func contains(_ text: String, _ needle: String) -> Bool {
        (text as NSString).range(of: needle, options: [.caseInsensitive]) != NSNotFound
    }

    private func snippet(_ line: String, _ needle: String) -> String {
        let ns = line as NSString
        let found = ns.range(of: needle, options: [.caseInsensitive])
        guard found.location != NSNotFound else { return String(line.prefix(60)) }
        let from = max(found.location - 18, 0)
        let to = min(found.location + found.length + 42, ns.length)
        guard to > from else { return String(line.prefix(60)) }
        return ns.substring(with: NSRange(location: from, length: to - from))
    }

    private func readRange(from byteStart: UInt64, to byteEnd: UInt64) -> String {
        guard byteEnd > byteStart else { return "" }
        let size = min(byteEnd - byteStart, UInt64(TextBookConstants.maxChapterBytes))
        guard let handle = try? FileHandle(forReadingFrom: url) else { return "" }
        defer { try? handle.close() }
        do {
            try handle.seek(toOffset: byteStart)
            let data = try handle.read(upToCount: Int(size)) ?? Data()
            return String(data: data, encoding: encoding.nsEncoding) ?? ""
        } catch {
            return ""
        }
    }
}

enum TextBookConstants {
    static let maxChapterBytes = 8 * 1024 * 1024
    static let maxParagraphChars = 1_400
    static let minParagraphChars = 600
    static let maxHitsPerChapter = 3
    static let titleMaxChars = 60
    /// 与 Android 版一致：认不出标题时的分段大小，也是单章的字符上限。
    static let maxChapterChars = 180_000

    static let textExtensions: Set<String> = [
        "txt", "md", "markdown", "log", "csv", "json", "xml", "yaml", "yml",
        "ini", "conf", "sql", "srt", "ass",
    ]

    static let sentenceStops: Set<Character> = [
        "。", "！", "？", "；", "：", "”", "’", "』", "】", "）",
        ".", "!", "?", ";", "\"", "'",
    ]

    /// 章节标题优先级：中文常见 → 卷/部 → 英文 → 序号开头 → 纯数字行。
    static let chapterPatterns: [NSRegularExpression] = [
        "^\\s*第\\s*[0-9〇零一二两三四五六七八九十百千]{1,8}\\s*[章回节篇话卷]{1,2}[\\s\\S]{0,40}$",
        "^\\s*(序\\s*[章言]|楔\\s*子|尾\\s*声|后\\s*记|前\\s*言|附\\s*言|番\\s*外[\\s\\S]{0,20})$",
        "(?i)^\\s*chapter\\s*[0-9ivxIVX]+[\\s\\S]{0,40}$",
        "^\\s*[0-9]{1,4}\\s*[.、．]\\s*\\S[\\s\\S]{0,40}$",
        "^\\s*[0-9]{1,5}\\s*$",
    ].compactMap { try? NSRegularExpression(pattern: $0) }

    static func isChapterTitle(_ line: String) -> Bool {
        guard (line as NSString).length <= titleMaxChars else { return false }
        let range = NSRange(location: 0, length: (line as NSString).length)
        return chapterPatterns.contains { $0.firstMatch(in: line, options: [], range: range) != nil }
    }
}

enum TextParagraphs {
    static func split(_ text: String) -> [String] {
        if text.isEmpty { return [] }
        var out: [String] = []
        for raw in text.components(separatedBy: "\n") {
            let paragraph = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if paragraph.isEmpty { continue }
            append(paragraph, to: &out)
        }
        return out
    }

    /// 整章没有换行时，也不能把一个几万字的大段落直接丢给 SwiftUI 排版。
    private static func append(_ paragraph: String, to out: inout [String]) {
        let characters = Array(paragraph)
        if characters.count <= TextBookConstants.maxParagraphChars {
            out.append(paragraph)
            return
        }
        var start = 0
        while start < characters.count {
            let hardEnd = min(start + TextBookConstants.maxParagraphChars, characters.count)
            let end = hardEnd < characters.count
                ? stopAfter(characters, from: hardEnd, floor: start + TextBookConstants.minParagraphChars)
                : hardEnd
            out.append(String(characters[start..<end]))
            start = end
        }
    }

    /// 往回找最近的句末标点，找不到就硬切；接缝仍是连续正文，看不出来。
    private static func stopAfter(_ characters: [Character], from: Int, floor: Int) -> Int {
        var index = from - 1
        while index > floor {
            if TextBookConstants.sentenceStops.contains(characters[index]) { return index + 1 }
            index -= 1
        }
        return from
    }
}

/// 扫一遍文件建章节索引，只记行首的（字节位置，字符位置），正文扫过就丢。
private final class TextIndexScanner {
    private let url: URL
    private let encoding: TextEncoding
    private let unit: Int

    private let chunkBytes = 256 * 1024
    private let maxLineBytes = 2 * 1024 * 1024
    private let fileSize: UInt64

    private var parts: [TextChapter] = []
    private var line = Data()

    private var charPos = 0
    private var lineCharStart = 0
    private var lineByteStart: UInt64 = 0

    /// nil = 这一章还没标题，落定前会补成「开篇」或「第 N 部分」。
    private var chapterTitle: String?
    private var chapterOrigin: String?
    private var chapterCharStart = 0
    private var chapterByteStart: UInt64 = 0
    private var continuation = 0
    private var hadMarker = false

    struct Result {
        let chapters: [TextChapter]
        let totalChars: Int
        let truncated: Bool
    }

    init(url: URL, encoding: TextEncoding) {
        self.url = url
        self.encoding = encoding
        self.unit = encoding.unit
        self.fileSize = UInt64(max((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0, 0))
    }

    func run() -> Result {
        guard fileSize > 0 else { return Result(chapters: singleChapter(byteEnd: 0, charEnd: 0), totalChars: 0, truncated: false) }
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            return Result(chapters: singleChapter(byteEnd: 0, charEnd: 0), totalChars: 0, truncated: true)
        }
        defer { try? handle.close() }

        var truncated = false
        do {
            let head = [UInt8](try handle.read(upToCount: Int(min(4, fileSize))) ?? Data())
            var base = UInt64(bomLength(head))
            lineByteStart = base
            chapterByteStart = base
            try handle.seek(toOffset: base)

            var position = base
            var buffer = [UInt8]()
            buffer.reserveCapacity(chunkBytes)
            while position < fileSize {
                let want = Int(min(UInt64(chunkBytes), fileSize - position))
                guard want > 0 else { break }
                buffer = [UInt8](try handle.read(upToCount: want) ?? Data())
                if buffer.isEmpty { break }
                // UTF-16 每轮只处理成双的字节，落单那个下一轮重读，省掉跨块缓存状态
                let usable = unit == 2 ? buffer.count - (buffer.count % 2) : buffer.count
                if usable <= 0 { break }

                var i = 0
                while i < usable {
                    if isBreak(buffer, i) {
                        let next = position + UInt64(i) + UInt64(unit)
                        closeLine(next, realNewline: true)
                        i += unit
                        lineByteStart = next
                    } else {
                        line.append(contentsOf: buffer[i..<(i + unit)])
                        i += unit
                    }
                }
                position += UInt64(usable)
                // 一整本没有换行时，行缓冲不能无限长
                if line.count >= maxLineBytes { cutOversizedLine(position) }
            }
            if !line.isEmpty { closeLine(fileSize, realNewline: false) }
            closeChapter(fileSize, charEnd: charPos)
        } catch {
            truncated = true
        }
        return Result(chapters: namedChapters(), totalChars: charPos, truncated: truncated)
    }

    private func bomLength(_ head: [UInt8]) -> Int {
        guard head.count >= 2 else { return 0 }
        if encoding == .utf8, head.count >= 3, head[0] == 0xEF, head[1] == 0xBB, head[2] == 0xBF { return 3 }
        if encoding == .utf16LE || encoding == .utf16BE,
           (head[0] == 0xFF && head[1] == 0xFE) || (head[0] == 0xFE && head[1] == 0xFF) { return 2 }
        return 0
    }

    private func isBreak(_ chunk: [UInt8], _ i: Int) -> Bool {
        switch encoding {
        case .utf16LE: return chunk[i] == 0x0A && chunk[i + 1] == 0x00
        case .utf16BE: return chunk[i] == 0x00 && chunk[i + 1] == 0x0A
        default: return chunk[i] == 0x0A
        }
    }

    private func closeLine(_ nextLineByteStart: UInt64, realNewline: Bool) {
        let bytes = line
        line = Data()
        // 保留 \r：它照样占一个字符，字符偏移才对得上真实解码结果，显示时靠 trim 去掉
        let text = String(data: bytes, encoding: encoding.nsEncoding) ?? ""
        let byteStart = lineByteStart
        let charStart = lineCharStart
        let newlineUnits = (realNewline && nextLineByteStart < fileSize) ? 1 : 0
        charPos += text.utf16.count + newlineUnits
        lineCharStart = charPos
        onLine(text.trimmingCharacters(in: .whitespacesAndNewlines), byteStart: byteStart, charStart: charStart)
    }

    /// 超长行强制分段：UTF-8 退回完整字符边界，避免切出一个乱码字符。
    private func cutOversizedLine(_ chunkEnd: UInt64) {
        let bytes = [UInt8](line)
        let cut = safeCut(bytes)
        let tail = bytes.count - cut
        let text = String(data: Data(bytes[0..<cut]), encoding: encoding.nsEncoding) ?? ""
        let byteStart = lineByteStart
        let charStart = lineCharStart
        charPos += text.utf16.count
        lineCharStart = charPos
        line = Data(bytes[cut...])
        lineByteStart = chunkEnd - UInt64(tail)
        onLine(text.trimmingCharacters(in: .whitespacesAndNewlines), byteStart: byteStart, charStart: charStart)
    }

    private func safeCut(_ bytes: [UInt8]) -> Int {
        guard encoding == .utf8 else { return bytes.count }
        var cut = bytes.count
        while cut > bytes.count - 4, cut > 0, bytes[cut - 1] >= 0x80 { cut -= 1 }
        return cut
    }

    private func onLine(_ trimmed: String, byteStart: UInt64, charStart: Int) {
        if trimmed.isEmpty { return }
        if TextBookConstants.isChapterTitle(trimmed) {
            hadMarker = true
            closeChapter(byteStart, charEnd: charStart)
            chapterTitle = trimmed
            chapterOrigin = trimmed
            continuation = 0
        } else if charStart - chapterCharStart >= TextBookConstants.maxChapterChars {
            closeChapter(byteStart, charEnd: charStart)
            continuation += 1
            chapterTitle = chapterOrigin.map { "\($0) · \(continuation)" }
        } else {
            return
        }
        chapterCharStart = charStart
        chapterByteStart = byteStart
    }

    private func closeChapter(_ byteEnd: UInt64, charEnd: Int) {
        guard byteEnd > chapterByteStart else { return }
        let fallback = parts.isEmpty ? "开篇" : "第 \(parts.count + 1) 部分"
        parts.append(TextChapter(title: chapterTitle ?? fallback,
                                 charStart: chapterCharStart, charEnd: charEnd,
                                 byteStart: chapterByteStart, byteEnd: byteEnd,
                                 order: parts.count))
        chapterCharStart = charEnd
        chapterByteStart = byteEnd
    }

    private func namedChapters() -> [TextChapter] {
        if parts.isEmpty {
            return singleChapter(byteEnd: fileSize, charEnd: max(charPos, 1))
        }
        // 一个标题都没认出来时，统一叫「第 N 部分」比「开篇 + 第 N 部分」顺
        if hadMarker { return parts }
        return parts.enumerated().map { index, chapter in chapter.renaming(to: "第 \(index + 1) 部分") }
    }

    private func singleChapter(byteEnd: UInt64, charEnd: Int) -> [TextChapter] {
        [TextChapter(title: "正文", charStart: chapterCharStart, charEnd: charEnd,
                     byteStart: chapterByteStart, byteEnd: byteEnd, order: 0)]
    }
}
