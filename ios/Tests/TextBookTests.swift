import Foundation
import XCTest

@testable import PocketVault

/// Android 版 `scratch/tbprobe` 那批 JVM 断言的对应实现：同一批性质在 Apple 的
/// Foundation 上再跑一遍，两个平台的一致性由测试保证而不是由我说。
final class TextBookTests: XCTestCase {

    private static let sentences = [
        "他抬起头，看见远山上的云正慢慢地散开。",
        "风从谷底吹上来，带着一股潮湿的草木气味。",
        "少年把书合上，掌心还剩一点冰凉的触感。",
        "远处传来几声犬吠，山谷里安静得像什么都没有发生过。",
        "她笑了笑，没有说什么，只是把灯芯拨亮了一些。",
    ]

    /// Big5 收不了简体字，测繁体得换一套繁体语料
    private static let traditionalSentences = [
        "他抬起頭，看見遠山上的雲正慢慢地散開。",
        "風從谷底吹上來，帶著一股潮濕的草木氣味。",
        "少年把書合上，掌心還剩一點冰涼的觸感。",
        "遠處傳來幾聲犬吠，山谷裡安靜得像什麼都沒有發生過。",
        "她笑了笑，沒有說什麼，只是把燈芯撥亮了一些。",
    ]

    private var bookDirectory: URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("textbook-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    // MARK: - 语料与断言工具

    private func novel(_ lines: [String], chapters: Int, linesPerChapter: Int, withMarkers: Bool = true) -> String {
        var text = ""
        for chapter in 1...chapters {
            if withMarkers { text += "第\(chapter)章 测试章节\n" }
            for line in 0..<linesPerChapter { text += lines[(chapter + line) % lines.count] + "\n" }
            text += "\n"
        }
        return text
    }

    @discardableResult
    private func write(_ name: String, _ text: String,
                       encoding: String.Encoding = .utf8, bom: [UInt8] = []) -> URL {
        let url = bookDirectory.appendingPathComponent(name)
        let body = text.data(using: encoding) ?? Data()
        try? Data(bom + [UInt8](body)).write(to: url)
        return url
    }

    private func flatten(_ book: TextBook) -> [String] {
        book.chapters.flatMap { book.paragraphs(of: $0.order) }
    }

    private func stripped(_ text: String) -> String {
        String(text.filter { !$0.isWhitespace })
    }

    /// 把每章段落拼回去（丢掉所有空白）必须等于原文丢空白——
    /// 一次覆盖"不丢内容、不重复、不乱码、边界不错位"四件事。
    private func assertComplete(_ book: TextBook, _ original: String,
                                file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(stripped(flatten(book).joined()), stripped(original),
                       "内容与原文不一致（丢段落、重复或字节边界错位）", file: file, line: line)
    }

    // MARK: - P0-1 编码判定

    func testLargeUtf8WithoutBomIsNotMistakenForGb18030() {
        let source = novel(Self.sentences, chapters: 300, linesPerChapter: 200)
        let url = write("utf8-big.txt", source)
        XCTAssertGreaterThan(url.fileSize, 512 * 1024, "语料必须大于采样窗口才测得到这个 bug")
        let book = TextBook.load(url)
        XCTAssertEqual(book.encoding, .utf8, "512K 采样切断汉字导致误判的老 bug 复发")
        assertComplete(book, source)
        XCTAssertEqual(book.chapters.count, 300)
    }

    func testGb18030Big5Utf16() {
        let source = novel(Self.sentences, chapters: 120, linesPerChapter: 120)
        let gb = write("gb18030.txt", source, encoding: TextEncoding.gb18030.nsEncoding)
        XCTAssertEqual(TextBook.load(gb).encoding, .gb18030)
        assertComplete(TextBook.load(gb), source)

        let traditional = novel(Self.traditionalSentences, chapters: 40, linesPerChapter: 60)
        let big5URL = write("big5.txt", traditional, encoding: TextEncoding.big5.nsEncoding)
        // Big5 字节流常被 GB18030 严格解码成功（它是超集），自动判定不保证准；
        // 这里要求的是"手动切到正确编码后内容精确一致"——那是界面上给用户的兜底。
        assertComplete(TextBook.load(big5URL, encodingOverride: .big5), traditional)

        let le = write("utf16le.txt", novel(Self.sentences, chapters: 40, linesPerChapter: 40),
                       encoding: .utf16LittleEndian, bom: [0xFF, 0xFE])
        let book16 = TextBook.load(le)
        XCTAssertEqual(book16.encoding, .utf16LE, "BOM 优先判定失效")
        assertComplete(book16, novel(Self.sentences, chapters: 40, linesPerChapter: 40))

        let beSource = novel(Self.sentences, chapters: 12, linesPerChapter: 12)
        let be = write("utf16be.txt", beSource, encoding: .utf16BigEndian, bom: [0xFE, 0xFF])
        XCTAssertEqual(TextBook.load(be).encoding, .utf16BE)
        assertComplete(TextBook.load(be), beSource)
    }

    func testBomCrlfAsciiAndEmoji() {
        let source = novel(Self.sentences, chapters: 5, linesPerChapter: 5)
        let withBom = write("bom.txt", source, bom: [0xEF, 0xBB, 0xBF])
        let book = TextBook.load(withBom)
        XCTAssertEqual(book.encoding, .utf8)
        XCTAssertEqual(book.paragraphs(of: 0).first, "第1章 测试章节", "BOM 混进了第一章正文")

        let crlf = write("crlf.txt", source.replacingOccurrences(of: "\n", with: "\r\n"))
        let crlfBook = TextBook.load(crlf)
        assertComplete(crlfBook, source)
        XCTAssertFalse(crlfBook.paragraphs(of: 0).contains { $0.contains("\r") }, "\r 没被清掉")

        let ascii = write("ascii.txt", "Chapter 1 hello\nline two\n")
        XCTAssertEqual(TextBook.load(ascii).encoding, .utf8)

        let emoji = write("emoji.txt", source.replacingOccurrences(of: "他抬起头", with: "他抬起头🙂"))
        XCTAssertEqual(TextBook.load(emoji).encoding, .utf8, "4 字节序列让末尾容忍判断失效")
    }

    // MARK: - Utf8Validator（Android 上由 JDK 保证，这里是自己写的，必须单独测）

    func testUtf8ValidatorStructuralRules() {
        XCTAssertEqual(Utf8Validator.validate([0x41, 0x42]), .valid)
        XCTAssertEqual(Utf8Validator.validate([0xE4, 0xB8, 0xAD]), .valid)
        XCTAssertEqual(Utf8Validator.validate([0xF0, 0x9F, 0x99, 0x82]), .valid)
        XCTAssertEqual(Utf8Validator.validate([]), .valid)

        // 末尾被切断不算非法——这正是 P0-1 的判定依据
        XCTAssertEqual(Utf8Validator.validate([0x41, 0xE4, 0xB8]), .truncatedTail)
        XCTAssertEqual(Utf8Validator.validate([0xF0, 0x9F]), .truncatedTail)

        // 中间断链 / 游离续字节 / 过长编码 / 代理区 / 超出范围
        XCTAssertEqual(Utf8Validator.validate([0xE4, 0x41, 0xAD]), .invalid(at: 1))
        XCTAssertEqual(Utf8Validator.validate([0x80, 0x41]), .invalid(at: 0))
        XCTAssertEqual(Utf8Validator.validate([0xC0, 0x80]), .invalid(at: 0))
        XCTAssertEqual(Utf8Validator.validate([0xE0, 0x80, 0x80]), .invalid(at: 1))
        XCTAssertEqual(Utf8Validator.validate([0xED, 0xA0, 0x80]), .invalid(at: 1))
        XCTAssertEqual(Utf8Validator.validate([0xF5, 0x80, 0x80, 0x80]), .invalid(at: 0))
        XCTAssertEqual(Utf8Validator.validate([0xF4, 0x90, 0x80, 0x80]), .invalid(at: 1))
    }

    // MARK: - 字节寻址与边界

    func testSingleLineWithoutNewlines() {
        let source = String(repeating: "甲", count: 300_000)
        let book = TextBook.load(write("oneline.txt", source))
        let parts = flatten(book)
        XCTAssertLessThanOrEqual(parts.map(\.count).max() ?? 0, 1_400, "超长段没补切")
        assertComplete(book, source)
    }

    func testOversizedLineForcedSplit() {
        // 6MB 单行：跨多个 256K 数据块，并触发超长行强制分段
        let source = String(repeating: "abc ", count: 1_500_000)
        let book = TextBook.load(write("oneline-huge.txt", source))
        XCTAssertGreaterThan(book.chapters.count, 1)
        assertComplete(book, source)
        XCTAssertTrue(flatten(book).allSatisfy { $0.count <= 1_400 })
    }

    func testChapterBoundaryInsideChunk() {
        let prefix = String(repeating: "字", count: 90_000)
        let source = prefix + "\n第2章 边界\n" + String(repeating: "尾", count: 500) + "\n"
        let book = TextBook.load(write("boundary.txt", source))
        assertComplete(book, source)
        XCTAssertEqual(book.chapters.count, 2)
    }

    func testLastLineWithoutTrailingNewline() {
        let source = "第1章 尾\n最后一行没有换行"
        let book = TextBook.load(write("tail.txt", source))
        assertComplete(book, source)
        XCTAssertEqual(book.paragraphs(of: 0).last, "最后一行没有换行")
    }

    func testMarkerlessFileFallsBackToParts() {
        let source = novel(Self.sentences, chapters: 200, linesPerChapter: 200, withMarkers: false)
        let book = TextBook.load(write("no-marker.txt", source))
        XCTAssertGreaterThan(book.chapters.count, 1, "认不出标题时应自动分段")
        XCTAssertTrue((book.chapters.first?.title ?? "").hasPrefix("第 1 部分"),
                      "首段标题：\(book.chapters.first?.title ?? "无")")
        assertComplete(book, source)
    }

    func testAdjacentTitlesDoNotCreateEmptyChapters() {
        var source = ""
        for index in 0..<500 { source += "第\(index)章\n" }
        source += "真正的正文。\n"
        let book = TextBook.load(write("adjacent.txt", source))
        XCTAssertFalse(book.chapters.contains { $0.byteEnd <= $0.byteStart }, "出现了 0 字节区间的章节")
        XCTAssertFalse(book.chapters.contains { $0.length <= 0 }, "出现了 0 字符区间的章节")
    }

    func testDegenerateFilesDoNotCrash() throws {
        XCTAssertEqual(TextBook.load(write("empty.txt", "")).totalChars, 0)

        let tiny = bookDirectory.appendingPathComponent("tiny.txt")
        try Data([0x41]).write(to: tiny)
        XCTAssertEqual(TextBook.load(tiny).chapters.count, 1)

        // UTF-16 末尾多一个落单字节：必须丢掉而不是死循环
        let odd = bookDirectory.appendingPathComponent("odd.txt")
        let body = "第1章 甲\n正文。\n".data(using: .utf16LittleEndian)!
        try Data([UInt8](body) + [0x41]).write(to: odd)
        let oddBook = TextBook.load(odd, encodingOverride: .utf16LE)
        XCTAssertEqual(oddBook.chapters.count, 1)
        XCTAssertEqual(oddBook.paragraphs(of: 0).count, 2)

        XCTAssertTrue(TextBook.load(write("blank.txt", "\n \n\t\n\n")).paragraphs(of: 0).isEmpty)
    }

    // MARK: - 搜索

    func testSearchHitsPointAtTheExactParagraph() async throws {
        var source = ""
        for chapter in 1...200 {
            source += "第\(chapter)章 标题\n"
            for line in 0..<30 { source += Self.sentences[(chapter + line) % Self.sentences.count] + "\n" }
        }
        source += "第201章 孤例\n这个罕见到位的名词只出现一次：紫金FLAME戒。\n"
        let book = TextBook.load(write("search.txt", source))

        let hits = try await book.search("犬吠")
        XCTAssertFalse(hits.isEmpty)
        for hit in hits {
            let paragraphs = book.paragraphs(of: hit.chapterIndex)
            XCTAssertTrue(paragraphs.indices.contains(hit.paragraphIndex),
                          "段落号越界：章 \(hit.chapterIndex) 段 \(hit.paragraphIndex)")
            XCTAssertTrue(paragraphs[hit.paragraphIndex].contains("犬吠"),
                          "命中段落号对不上实际内容：章 \(hit.chapterIndex) 段 \(hit.paragraphIndex)")
        }
        XCTAssertGreaterThanOrEqual(Set(hits.map(\.chapterIndex)).count, 5, "高频词结果被前面几章吃满")
        XCTAssertLessThanOrEqual(Dictionary(grouping: hits, by: \.chapterIndex).values.map(\.count).max() ?? 0, 3)
        XCTAssertTrue(hits.contains { $0.preview.contains("本章命中") }, "超额那章应标注命中总数")

        let rare = try await book.search("紫金FLAME戒")
        XCTAssertEqual(rare.count, 1)
        XCTAssertEqual(rare.first?.chapterIndex, 200, "只出现在最后一章的词必须还能命中")

        XCTAssertFalse(try await book.search("flame").isEmpty, "忽略大小写失效")
        XCTAssertTrue(try await book.search("   ").isEmpty)
    }

    // MARK: - 性能（数字打进 CI 日志，作为 P2-1 的现场证据）

    func testLargeFileStaysFast() async throws {
        let source = novel(Self.sentences, chapters: 2000, linesPerChapter: 200)
        let url = write("speed.txt", source)
        let size = url.fileSize

        let started = Date()
        let book = TextBook.load(url)
        let loadMs = elapsed(since: started)

        let sliceStarted = Date()
        let last = book.paragraphs(of: book.chapters.count - 1)
        let sliceMs = elapsed(since: sliceStarted)

        let searchStarted = Date()
        let hits = try await book.search("犬吠", limit: 80)
        let searchMs = elapsed(since: searchStarted)

        let walkStarted = Date()
        let paragraphCount = book.chapters.reduce(0) { $0 + book.paragraphs(of: $1.order).count }
        let walkMs = elapsed(since: walkStarted)

        print("PERF \(size / 1024 / 1024)MB / \(book.chapters.count) 章：" +
              "建索引 \(loadMs)ms，取末章 \(sliceMs)ms（\(last.count) 段），" +
              "搜索 \(searchMs)ms（\(hits.count) 条），全书取段 \(walkMs)ms（\(paragraphCount) 段）")

        XCTAssertLessThan(loadMs, 8_000, "建索引退化")
        XCTAssertLessThan(sliceMs, 300, "取单章应当与文件大小无关")
        XCTAssertLessThan(walkMs, max(loadMs * 3, 3_000), "全书逐章取段接近单遍扫描才算寻址正确")
        XCTAssertLessThan(searchMs, 8_000)
    }

    private func elapsed(since date: Date) -> Int {
        Int(Date().timeIntervalSince(date) * 1000)
    }
}

private extension URL {
    var fileSize: Int {
        (try? resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    }
}
