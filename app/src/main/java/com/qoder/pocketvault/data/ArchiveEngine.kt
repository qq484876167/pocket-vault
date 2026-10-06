package com.qoder.pocketvault.data

import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.core.formatBytes
import com.qoder.pocketvault.core.joinRelative
import com.qoder.pocketvault.data.db.VaultEntry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

/** 同名冲突时怎么处理。RENAME 就是原来的静默改名，现在由用户挑。 */
enum class ConflictPolicy { RENAME, OVERWRITE, SKIP }

/** 界面给的四档；STORE 不用 DEFLATE，映射到 zip4j 的 STORE / DEFLATE + 具体级别。 */
enum class ArchiveCompression(val label: String) {
    STORE("存储"),
    FAST("快速"),
    NORMAL("标准"),
    ULTRA("极限"),
    ;

    val method: CompressionMethod
        get() = if (this == STORE) CompressionMethod.STORE else CompressionMethod.DEFLATE

    /** zip4j 没有"存储"这一档（STORE 由 CompressionMethod 表达），给个不被使用的值。 */
    val level: CompressionLevel
        get() = when (this) {
            STORE -> CompressionLevel.NO_COMPRESSION
            FAST -> CompressionLevel.FAST
            NORMAL -> CompressionLevel.NORMAL
            ULTRA -> CompressionLevel.ULTRA
        }
}

/** 解压前的计划：总量、冲突数、空间够不够、要不要密码。 */
data class ExtractPlan(
    val fileCount: Int,
    val totalBytes: Long,
    val conflictCount: Int,
    val skippedLinkCount: Int,
    val needsPassword: Boolean,
    val requiredBytes: Long,
    val availableBytes: Long,
    /** false 表示应当拒绝开工：空间明显不够。 */
    val enoughSpace: Boolean,
    /** 「解压到同名新文件夹」时，目标位置上是否已有同名文件夹。 */
    val targetFolderExists: Boolean = false,
    /** 已存在时改名后的落点（「原名 (2)」），供界面写清楚。 */
    val suggestedFolder: String = "",
)

/** 解压结果。计数分开给，界面才能说清"改名 N、覆盖 M、跳过 K"。 */
data class ExtractSummary(
    val written: Int,
    val renamed: Int,
    val overwritten: Int,
    val skipped: Int,
    val skippedLinks: Int,
    val attempted: Int,
    val destRelativePath: String,
    /** 本次新建出来的顶层目录；没有则为 null，用于"进入该文件夹"与一键清理。 */
    val createdFolder: String?,
    val createdEntryIds: List<Long>,
    /** 中途失败的原因；null 表示顺利跑完。失败时已解压的部分仍留在库里等用户决定。 */
    val error: String?,
) {
    val partial: Boolean get() = error != null
}

/**
 * 压缩包的浏览 / 解压 / 打包。格式差异都收在 [ArchiveReaders] 里，
 * 这里只做"落到文件库"这一件事：包内条目名一律经 [ArchiveEntryNames.sanitize]，
 * 所有写入一律走 repo.allocateFile / commitFile / discardAllocation。
 */
class ArchiveEngine(private val repo: VaultRepository) {

    /** 保留旧名字，语义换成三态：EXTRACTABLE / PASSWORD_REQUIRED / UNSUPPORTED。 */
    fun supportFor(archive: File): ArchiveCapability = when (ArchiveFormat.detect(archive.name)) {
        ArchiveFormat.UNSUPPORTED -> ArchiveCapability.UNSUPPORTED
        else -> runCatching {
            ArchiveReaders.open(archive, null).use { it.capability }
        }.getOrElse { e ->
            if (e is WrongArchivePasswordException) ArchiveCapability.PASSWORD_REQUIRED
            else ArchiveCapability.UNSUPPORTED
        }
    }

    /** 列出包内条目；密码缺失/错误、格式不支持都抛出可读提示。 */
    suspend fun list(archive: File, password: String?): List<ArchiveItem> = withContext(Dispatchers.IO) {
        requireReadable(archive)
        ArchiveReaders.open(archive, password).use { reader -> reader.entries() }
    }

    /**
     * 解压前的计划：给出条目数、字节总数、与目标目录的同名冲突数，
     * 以及空间够不够。availableBytes 传 <=0 表示拿不到，此时不误报、放行。
     */
    suspend fun plan(
        archive: File,
        destDirRelativePath: String,
        password: String?,
        policy: ConflictPolicy,
        availableBytes: Long,
    ): ExtractPlan = withContext(Dispatchers.IO) {
        requireReadable(archive)
        val dest = VaultPaths.normalizeRelative(destDirRelativePath)
        ArchiveReaders.open(archive, password).use { reader ->
            val items = reader.entries()
            val files = items.filter { !it.isDirectory }
            val links = files.count { !it.regularFile }
            val needToCheck = if (policy == ConflictPolicy.SKIP) files else files.filter { it.regularFile }
            val conflicts = needToCheck.count { repo.entryAt(joinRelative(dest, it.displayName)) != null }
            val required = reader.totalBytes(items)
            val enough = availableBytes <= 0L || required < 0L || required <= availableBytes - SAFETY_MARGIN
            val folderName = VaultPaths.sanitizeName(archive.name.substringBeforeLast('.', archive.name))
            val preferred = joinRelative(dest, folderName)
            val alreadyThere = repo.entryAt(preferred) != null
            ExtractPlan(
                targetFolderExists = alreadyThere,
                suggestedFolder = if (alreadyThere) freeFolderPath(dest, folderName) else preferred,
                fileCount = files.size,
                totalBytes = required,
                conflictCount = conflicts,
                skippedLinkCount = links,
                needsPassword = reader.capability == ArchiveCapability.PASSWORD_REQUIRED,
                requiredBytes = required,
                availableBytes = availableBytes,
                enoughSpace = enough,
            )
        }
    }

    /** 目标目录里已有同名条目、以及要新建的同名文件夹是否存在。 */
    suspend fun folderExists(destDirRelativePath: String): Boolean = withContext(Dispatchers.IO) {
        val clean = VaultPaths.normalizeRelative(destDirRelativePath)
        clean.isNotEmpty() && repo.entryAt(clean) != null
    }

    /** 在 dest 下找一个还不存在的文件夹名（「原名 (2)」式递增），用于"自动改名"。 */
    suspend fun freeFolderPath(parentRelativePath: String, rawName: String): String =
        withContext(Dispatchers.IO) {
            val parent = VaultPaths.normalizeRelative(parentRelativePath)
            val base = VaultPaths.sanitizeName(rawName.substringBeforeLast('.', rawName))
            var candidate = joinRelative(parent, base)
            var index = 2
            while (repo.entryAt(candidate) != null) {
                candidate = joinRelative(parent, "$base ($index)")
                index++
                if (index > MAX_RENAME_ATTEMPTS) break
            }
            candidate
        }

    /**
     * 解压整包到文件库内的某个目录，保留包内层级。
     * [folderName] 非空时先在 dest 下建这个同名新文件夹（"解压到同名新文件夹"的默认行为），
     * 结果落点由返回的 [ExtractSummary.destRelativePath] 给出。
     */
    suspend fun extractToVault(
        archive: File,
        destDirRelativePath: String,
        password: String?,
        onProgress: (written: Long, total: Long) -> Unit,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
        folderName: String? = null,
    ): ExtractSummary = withContext(Dispatchers.IO) {
        requireReadable(archive)
        val requested = VaultPaths.normalizeRelative(destDirRelativePath)
        val tally = Tally()
        val ids = ArrayList<Long>()
        var error: String? = null
        var attempted = 0
        var dest = requested
        var createdFolder: String? = null

        ArchiveReaders.open(archive, password).use { reader ->
            val all = reader.entries()
            // 先把账算完再动手：空间明显不够就拒绝开工，不留下解了一半的包
            refuseIfNoSpace(reader.totalBytes(all))
            if (!folderName.isNullOrBlank()) {
                val target = joinRelative(requested, VaultPaths.sanitizeName(folderName))
                // 已存在则按调用方决定好的"合并"处理，不再改名
                if (repo.entryAt(target) == null) {
                    repo.ensureFolderPath(target)
                    createdFolder = target
                }
            }
            dest = createdFolder ?: requested
            // 先建目录层级；目标位置是文件的跳过，避免 ensureFolderPath 抛异常打断整包
            for (item in all.filter { it.isDirectory }) {
                val rel = joinRelative(dest, item.displayName)
                val existing = repo.entryAt(rel)
                if (existing == null) repo.ensureFolderPath(rel)
                else if (!existing.isFolder) tally.skipped++
            }
            val files = all.filter { !it.isDirectory }
            attempted = files.size
            val total = reader.totalBytes(all)
            val writable = files.filter { it.regularFile }
            tally.skippedLinks = files.size - writable.size
            var writtenLocally = 0L
            try {
                reader.forEachContent(writable) { item, stream ->
                    currentCoroutineContext().ensureActive()
                    val id = writeEntry(item, stream, dest, policy, tally, ids)
                    if (id != null) {
                        writtenLocally += item.sizeBytes.coerceAtLeast(0)
                        onProgress(writtenLocally, total)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.messageSafe()
            }
        }
        ExtractSummary(
            written = tally.written,
            renamed = tally.renamed,
            overwritten = tally.overwritten,
            skipped = tally.skipped,
            skippedLinks = tally.skippedLinks,
            attempted = attempted,
            destRelativePath = dest,
            createdFolder = createdFolder,
            createdEntryIds = ids,
            error = error,
        )
    }

    /** 只提取包内一个条目（条目行点击用）。返回落地的条目。 */
    suspend fun extractEntryToVault(
        archive: File,
        entry: ArchiveItem,
        destDirRelativePath: String,
        password: String?,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
    ): VaultEntry? = withContext(Dispatchers.IO) {
        requireReadable(archive)
        if (!entry.regularFile) throw ArchiveOpenException("链接类条目不能提取")
        val dest = VaultPaths.normalizeRelative(destDirRelativePath)
        val tally = Tally()
        val ids = ArrayList<Long>()
        ArchiveReaders.open(archive, password).use { reader ->
            reader.content(entry).use { stream ->
                writeEntry(entry, stream, dest, policy, tally, ids)
            }
        }
        ids.firstOrNull()?.let { repo.entryById(it) }
    }

    /** 把包内一个条目解到临时文件，给预览用（不进库、不建索引）。 */
    suspend fun extractToTemp(
        archive: File,
        entry: ArchiveItem,
        password: String?,
        targetNameHint: String,
    ): File? = withContext(Dispatchers.IO) {
        requireReadable(archive)
        if (!entry.regularFile) return@withContext null
        val suffix = targetNameHint.substringAfterLast('.', "")
        val temp = File.createTempFile("preview-", if (suffix.isEmpty()) "" else ".$suffix")
        runCatching {
            ArchiveReaders.open(archive, password).use { reader ->
                reader.content(entry).use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, BUFFER)
                    }
                }
            }
        }.onFailure {
            runCatching { temp.delete() }
            throw it
        }
        temp
    }

    /** 打包选中的文件/文件夹为 zip 写回文件库。 */
    suspend fun compressToVault(
        entries: List<VaultEntry>,
        zipName: String,
        destDirRelativePath: String,
        password: String?,
        level: ArchiveCompression = ArchiveCompression.NORMAL,
    ): VaultEntry = withContext(Dispatchers.IO) {
        require(entries.isNotEmpty()) { "请先选择要压缩的项目" }
        val dest = VaultPaths.normalizeRelative(destDirRelativePath)
        val name = VaultPaths.sanitizeName(zipName).let { if (it.endsWith(".zip")) it else "$it.zip" }
        val allocation = repo.allocateFile(dest, name)
        val temp = allocation.tempFile
        temp.parentFile?.mkdirs()
        val params = ZipParameters().apply {
            // zip4j 用 isX()/setX() 组合，Kotlin 不会把它们当合成属性，必须显式调用 setter
            setCompressionMethod(level.method)
            setCompressionLevel(level.level)
            setIncludeRootFolder(false)
            if (!password.isNullOrEmpty()) {
                setEncryptFiles(true)
                setEncryptionMethod(EncryptionMethod.AES)
                setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
            }
        }
        try {
            openZip(temp, password).use { zip ->
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

    // ---------------------------------------------------------------- 内部

    /** 单个条目落到库里；返回新条目 id，跳过时返回 null。整包与单条提取共用这一段。 */
    private suspend fun writeEntry(
        item: ArchiveItem,
        stream: java.io.InputStream,
        dest: String,
        policy: ConflictPolicy,
        tally: Tally,
        createdIds: MutableList<Long>,
    ): Long? {
        val rel = joinRelative(dest, item.displayName)
        val dirRel = rel.substringBeforeLast('/', "")
        val leafName = rel.substringAfterLast('/')
        val existing = repo.entryAt(rel)
        if (existing != null) {
            when (policy) {
                ConflictPolicy.SKIP -> {
                    tally.skipped++
                    return null
                }
                // 覆盖目录太危险（会连带删掉整棵子树），退化成另存一份
                ConflictPolicy.OVERWRITE -> if (existing.isFolder) {
                    tally.renamed++
                } else {
                    repo.deletePermanently(listOf(existing.id))
                    tally.overwritten++
                }
                ConflictPolicy.RENAME -> tally.renamed++
            }
        }
        val allocation = repo.allocateFile(dirRel, leafName)
        return try {
            FileOutputStream(allocation.tempFile).use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            val committed = repo.commitFile(
                allocation = allocation,
                sourceSignature = null,
                sourceModifiedAt = item.modifiedAtMillis.takeIf { it > 0 },
                declaredSize = item.sizeBytes.takeIf { it > 0 },
                mimeType = null,
            )
            createdIds += committed.id
            tally.written++
            committed.id
        } catch (e: Exception) {
            repo.discardAllocation(allocation)
            throw e
        }
    }

    private class Tally {
        var written = 0
        var renamed = 0
        var overwritten = 0
        var skipped = 0
        var skippedLinks = 0
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

    private fun openZip(target: File, password: String?): ZipFile =
        if (password.isNullOrEmpty()) ZipFile(target) else ZipFile(target, password.toCharArray())

    private fun requireReadable(archive: File) {
        if (!archive.isFile) throw ArchiveOpenException("压缩包不存在，或仍在回收站中")
    }

    /** 空间明显不够就拒绝开工。requiredBytes 或可用量拿不到时不误报、放行。 */
    private fun refuseIfNoSpace(requiredBytes: Long) {
        val free = repo.usableBytes()
        if (requiredBytes < 0L || free <= 0L) return
        if (requiredBytes > free - SAFETY_MARGIN) {
            throw ArchiveOpenException("空间不够：需要约 ${formatBytes(requiredBytes)}，可用 ${formatBytes(free)}")
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024

        /** 留一点余量：解压过程中的临时文件与库本身共用一个分区。 */
        const val SAFETY_MARGIN = 32L * 1024 * 1024
        const val MAX_RENAME_ATTEMPTS = 500
    }
}
