package com.qoder.pocketvault.data

import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.core.joinRelative
import com.qoder.pocketvault.data.db.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class ArchiveItem(
    val storedName: String,
    val displayName: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val encrypted: Boolean,
)

/**
 * 压缩包的浏览 / 解压 / 打包。zip 内部条目名属于外部不可信输入，
 * 一律经过清洗与越界校验（zip-slip 防线见 [sanitizeEntryPath]）。
 */
class ArchiveEngine(private val repo: VaultRepository) {

    enum class Support { ZIP_READABLE, LIST_ONLY, UNKNOWN }

    fun supportFor(archive: File): Support = when (FileKind.extensionOf(archive.name)) {
        "zip", "jar", "apk", "cbz" -> Support.ZIP_READABLE
        "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "cbr", "iso" -> Support.LIST_ONLY
        else -> Support.UNKNOWN
    }

    /** 列出包内条目；需要密码而密码缺失/错误时抛出可读提示。 */
    suspend fun list(archive: File, password: String?): List<ArchiveItem> = withContext(Dispatchers.IO) {
        requireReadable(archive)
        open(archive, password).use { zip ->
            zip.fileHeaders.mapNotNull { header ->
                val safe = sanitizeEntryPath(header.fileName) ?: return@mapNotNull null
                ArchiveItem(
                    storedName = header.fileName,
                    displayName = safe,
                    isDirectory = header.isDirectory,
                    sizeBytes = header.uncompressedSize,
                    compressedBytes = header.compressedSize,
                    encrypted = header.isEncrypted,
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.displayName }))
        }
    }

    /** 解压整包到文件库内的某个目录，保留包内层级。 */
    suspend fun extractToVault(
        archive: File,
        destDirRelativePath: String,
        password: String?,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        requireReadable(archive)
        val dest = VaultPaths.normalizeRelative(destDirRelativePath)
        open(archive, password).use { zip ->
            val headers = zip.fileHeaders
            val total = headers.filter { !it.isDirectory }.sumOf { it.uncompressedSize.coerceAtLeast(0) }
            var written = 0L
            var count = 0
            for (header in headers.filter { it.isDirectory }) {
                val safe = sanitizeEntryPath(header.fileName) ?: continue
                repo.ensureFolderPath(joinRelative(dest, safe))
            }
            for (header in headers.filter { !it.isDirectory }) {
                currentCoroutineContext().ensureActive()
                val safe = sanitizeEntryPath(header.fileName) ?: continue
                val allocation = repo.allocateFile(
                    dirRelativePath = joinRelative(dest, safe.substringBeforeLast('/', "")),
                    rawName = safe.substringAfterLast('/'),
                )
                try {
                    zip.getInputStream(header).use { input ->
                        FileOutputStream(allocation.tempFile).use { output ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                onProgress(written, total)
                            }
                            output.fd.sync()
                        }
                    }
                    repo.commitFile(
                        allocation = allocation,
                        sourceSignature = null,
                        sourceModifiedAt = header.lastModifiedTime.takeIf { it > 0 },
                        declaredSize = header.uncompressedSize.takeIf { it > 0 },
                        mimeType = null,
                    )
                    count++
                } catch (e: Exception) {
                    repo.discardAllocation(allocation)
                    throw e
                }
            }
            count
        }
    }

    /** 打包选中的文件/文件夹为 zip 写回文件库，尽量保留用户看到的名称与层级。 */
    suspend fun compressToVault(
        entries: List<VaultEntry>,
        zipName: String,
        destDirRelativePath: String,
        password: String?,
    ): VaultEntry = withContext(Dispatchers.IO) {
        require(entries.isNotEmpty()) { "请先选择要压缩的项目" }
        val dest = VaultPaths.normalizeRelative(destDirRelativePath)
        val name = VaultPaths.sanitizeName(zipName).let { if (it.endsWith(".zip")) it else "$it.zip" }
        val allocation = repo.allocateFile(dest, name)
        val temp = allocation.tempFile
        temp.parentFile?.mkdirs()
        val params = ZipParameters().apply {
            // zip4j 用 isX()/setX() 组合，Kotlin 不会把它们当合成属性，必须显式调用 setter
            setCompressionMethod(CompressionMethod.DEFLATE)
            setIncludeRootFolder(false)
            if (!password.isNullOrEmpty()) {
                setEncryptFiles(true)
                setEncryptionMethod(EncryptionMethod.AES)
                setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
            }
        }
        try {
            open(temp, password).use { zip ->
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    val physical = runCatching { repo.physicalFile(entry) }.getOrNull()
                    if (physical == null || !physical.exists()) continue
                    if (entry.isFolder) {
                        var added = 0
                        zipFolder(zip, entry, entry.name, params) { added++ }
                        if (added == 0) {
                            params.fileNameInZip = entry.name + "/"
                            zip.addStream(java.io.ByteArrayInputStream(ByteArray(0)), params)
                        }
                    } else {
                        params.fileNameInZip = entry.name
                        physical.inputStream().use { zip.addStream(it, params) }
                    }
                }
            }
            repo.commitFile(
                allocation = allocation,
                sourceSignature = null,
                sourceModifiedAt = null,
                declaredSize = temp.length(),
                mimeType = "application/zip",
            )
        } catch (e: Exception) {
            repo.discardAllocation(allocation)
            throw e
        }
    }

    private suspend fun zipFolder(
        zip: ZipFile,
        entry: VaultEntry,
        prefix: String,
        params: ZipParameters,
        onAdded: () -> Unit,
    ) {
        val children = repo.childrenOf(entry)
        if (children.isEmpty()) return
        for (child in children) {
            currentCoroutineContext().ensureActive()
            val physical = runCatching { repo.physicalFile(child) }.getOrNull() ?: continue
            if (!physical.exists()) continue
            if (child.isFolder) {
                zipFolder(zip, child, "$prefix/${child.name}", params, onAdded)
            } else {
                params.fileNameInZip = "$prefix/${child.name}"
                physical.inputStream().use { zip.addStream(it, params) }
                onAdded()
            }
        }
    }

    private fun open(target: File, password: String?): ZipFile =
        if (password.isNullOrEmpty()) ZipFile(target) else ZipFile(target, password.toCharArray())

    private fun requireReadable(archive: File) {
        if (!archive.isFile) throw IOException("压缩包不存在，或仍在回收站中")
    }

    /**
     * 包内条目名 -> 安全相对路径。拒绝绝对路径、盘符、`..` 与保留名；
     * 返回 null 表示跳过该条目。
     */
    private fun sanitizeEntryPath(raw: String): String? {
        val unified = raw.replace('\\', '/').trimStart()
        if (unified.isEmpty() || unified.startsWith("/")) return null
        if (unified.matches(Regex("^[A-Za-z]:.*"))) return null
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        val cleaned = segments.map { VaultPaths.sanitizeName(it) }
        val joined = cleaned.joinToString("/")
        return try {
            VaultPaths.normalizeRelative(joined).takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
