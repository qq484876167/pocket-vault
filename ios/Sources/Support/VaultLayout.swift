import Foundation

/// 私密内容的落点。
///
/// iOS 上"隔离"是沙盒默认行为，真正要做的是两件事：待在私有容器里、且不参与备份。
/// 位置选 Application Support 而不是 Documents——后者在开启文件共享时会被 Files.app 露出。
enum VaultLayout {

    static let vaultDirectoryName = "vault"
    static let trashDirectoryName = "trash"

    static func containerRoot(in fileManager: FileManager = .default) throws -> URL {
        try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
    }

    static func vaultRoot(in container: URL) -> URL {
        container.appendingPathComponent(vaultDirectoryName, isDirectory: true)
    }

    /// 建好 vault 与回收站，并把它们标记为不参与 iCloud / iTunes 备份。
    @discardableResult
    static func prepare(in fileManager: FileManager = .default) throws -> URL {
        let root = vaultRoot(in: try containerRoot(fileManager: fileManager))
        for directory in [root, root.appendingPathComponent(trashDirectoryName, isDirectory: true)] {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try directory.excludedFromBackup()
        }
        return root
    }
}

extension URL {
    /// 排除备份只能逐个资源设置，目录不会自动向下继承。
    func excludedFromBackup() throws {
        var values = try resourceValues(forKeys: [.isExcludedFromBackupKey])
        values.isExcludedFromBackup = true
        try setResourceValues(values)
    }
}
