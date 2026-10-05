import AVFoundation
import Foundation
import PDFKit
import XCTest
import UniformTypeIdentifiers

@testable import PocketVault

/// 第一阶段只验平台假设：这些结论决定后面怎么写，所以先让它们在真 SDK 上跑一遍。
final class PipelineProbeTests: XCTestCase {

    /// GB18030 / Big5 在 String.Encoding 里没有具名 case，只能经 CoreFoundation 转换。
    /// 若这里的常量形式与本 SDK 不符，CI 的报错文字就是正确答案。
    private func encoding(_ id: CFStringEncodings) -> String.Encoding {
        String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(id.rawValue)))
    }

    func testChineseEncodingsRoundTrip() throws {
        let simplified = "口袋文件库，远山上的云正慢慢地散开。"
        let traditional = "口袋文件庫，遠山上的雲正慢慢地散開。"

        let cases: [(String, String.Encoding, String)] = [
            ("UTF-8", .utf8, simplified),
            ("GB18030", encoding(.GB_18030_2000), simplified),
            ("Big5", encoding(.Big5), traditional),
            ("UTF-16", .utf16, simplified),
        ]
        for (label, encoding, text) in cases {
            let data = text.data(using: encoding)
            XCTAssertNotNil(data, "\(label) 在本 SDK 上编码失败")
            let decoded = data.flatMap { String(data: $0, encoding: encoding) }
            XCTAssertEqual(decoded, text, "\(label) 往返后不一致：\(decoded ?? "nil")")
        }
    }

    func testVaultIsPrivateAndExcludedFromBackup() throws {
        let root = VaultLayout.vaultRoot(in: FileManager.default.temporaryDirectory)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let file = root.appendingPathComponent("probe.txt")
        let payload = "probe".data(using: .utf8)!
        try payload.write(to: file, options: .atomic)

        try VaultLayout.excludeFromBackup(file)
        let readBack = try file.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(readBack.isExcludedFromBackup, true, "排除备份标记没生效")

        XCTAssertEqual(try Data(contentsOf: file), payload)
        // vault 不能待在 Documents：那是开启文件共享后会露出来的位置
        XCTAssertFalse(root.path.contains("/Documents"), "vault 落在 Documents 会被 Files.app 暴露")
        XCTAssertTrue(root.lastPathComponent == "vault")

        // 真实容器里的路径也应当位于 Application Support
        let real = try VaultLayout.vaultRoot()
        XCTAssertTrue(real.path.contains("Application Support"), "实际落点：\(real.path)")
    }

    func testSystemFrameworksCoverThePlan() throws {
        // PDF 交给 PDFKit 原生渲染，不再走位图缓存那条路
        let document = PDFDocument()
        XCTAssertEqual(document.pageCount, 0)
        XCTAssertNil(document.page(at: 0))

        // 视频/音频用系统播放栈
        let player = AVPlayer(url: URL(fileURLWithPath: "/dev/null"))
        XCTAssertNotNil(player)

        // 文档选择器的内容类型
        XCTAssertEqual(UTType.folder.identifier, "public.folder")
        XCTAssertEqual(UTType.pdf.identifier, "com.adobe.pdf")
        XCTAssertEqual(UTType.plainText.identifier, "public.plain-text")
        XCTAssertTrue(UTType.movie.identifier.hasPrefix("public."))
    }

    func testTopLevelNavigationMatchesAndroid() {
        XCTAssertEqual(TopDestination.allCases.map(\.rawValue), ["文件库", "目录", "搜索", "回收站", "设置"])
    }
}
