package com.qoder.pocketvault.data

import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.core.joinRelative
import com.qoder.pocketvault.data.db.VaultEntry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    /** 因体积超限被拒的第一个条目的原因；界面用它解释"跳过"到底是为什么跳过。 */
    val refusalNote: String? = null,
) {
    val partial: Boolean get() = error != null
}

/**
 * 压缩包的浏览 / 解压 / 打包。格式差异都收在 [ArchiveReaders] 里，
 * 这里只做"落到文件库"这一件事：包内条目名一律经 [ArchiveEntryNames.sanitize]，
 * 所有写入一律走 repo.allocateFile / commitFile / discardAllocation。
 */
class ArchiveEngine(private val repo: VaultRepository) {

    /**
     * 一次开包同时完成"探能力 + 验密码 + 列条目"。
     *
     * 探测必须带着密码去做：以前的 supportFor 固定用 null 开包，加密包永远返回"要密码"，
     * 用户输了正确密码也进不到 EXTRACTABLE 分支。而"能列出条目名"同样证明不了密码对
     * （zip 的中央目录不加密，实测 zipcrypto / aes256 / mixed 三种包无密码都能列出），
     * 所以这里对加密条目真读 16 个字节来判定。
     */
    suspend fun access(archive: File, password: String?): ArchiveAccess = withContext(Dispatchers.IO) {
        val format = ArchiveFormat.detect(archive.name)
        if (format == ArchiveFormat.UNSUPPORTED) {
            return@withContext ArchiveAccess.Unsupported(ArchiveFormat.unsupportedReason(archive.name))
        }
        val outcome = runCatching {
            // 条目不在库里（被移进回收站 / 已删除）也归成 Broken，不能把异常抛到界面线程的协程外
            requireReadable(archive)
            ArchiveReaders.open(archive, password).use { reader ->
                // 先验密码再列目录：zip 的中央目录不加密，列得出来不代表密码对
                reader.probeReadable()
                reader.entries()
            }
        }
        archiveAccessOf(password, outcome)
    }

    /**
     * 解压前的计划：给出条目数、字节总数、与**真实落点**的同名项数量，以及空间够不够。
     * 同名项数量与策略无关（界面按所选策略把它读成"会跳过/会另存/会覆盖 N 项"），
     * availableBytes 传 <=0 表示拿不到，此时不误报、放行。
     * [knownItems] 是界面已经列出来的条目，传进来可以省掉 tar 家族的第二次整包扫描。
     * [landingDirRelativePath] 是真实落点（"同名新文件夹"时指那个文件夹），
     * 不给就按 [destDirRelativePath] 算；同名文件夹是否存在仍然只看 [destDirRelativePath]。
     */
    suspend fun plan(
        archive: File,
        destDirRelativePath: String,
        password: String?,
        availableBytes: Long,
        knownItems: List<ArchiveItem>? = null,
        landingDirRelativePath: String? = null,
    ): ExtractPlan = withContext(Dispatchers.IO) {
        requireReadable(archive)
        val dest = VaultPaths.normalizeRelative(destDirRelativePath)
        // 冲突要按真实落点数：选"同名新文件夹"时落点是那个文件夹本身，而不是它的父目录
        val conflictDir = VaultPaths.normalizeRelative(landingDirRelativePath ?: dest)
        val budget = ExtractBudget.forVault()
        ArchiveReaders.open(archive, password).use { reader ->
            // tar 家族列一次目录就要整包扫一遍，界面已经列过时直接把结果传进来复用
            val items = knownItems ?: reader.entries()
            val files = items.filter { !it.isDirectory }
            val links = files.count { !it.regularFile }
            // 链接类条目根本不落盘，把它们算进"冲突"只会误导
            val conflicts = files.count { it.regularFile && repo.entryAt(joinRelative(conflictDir, it.displayName)) != null }
            val required = reader.totalBytes(items)
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
                enoughSpace = budget.refuseBeforeStart(required, availableBytes) == null,
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
     * [folderName] 非空时落点是 dest 下的这个文件夹：不存在就新建（[ExtractSummary.createdFolder]
     * 会标出来），已存在就把条目合并进去——两种情况都不会把文件倒在 dest 里。
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
        // 每次解压一份独立预算：累计字节与复查节奏都只在这一包里算
        val budget = ExtractBudget.forVault()

        ArchiveReaders.open(archive, password).use { reader ->
            val all = reader.entries()
            // 先把账算完再动手：空间明显不够就拒绝开工，不留下解了一半的包
            budget.refuseBeforeStart(reader.totalBytes(all), repo.usableBytes())
                ?.let { throw ArchiveOpenException(it) }
            if (!folderName.isNullOrBlank()) {
                val target = joinRelative(requested, VaultPaths.sanitizeName(folderName))
                val existing = repo.entryAt(target)
                when {
                    existing == null -> {
                        repo.ensureFolderPath(target)
                        createdFolder = target
                    }
                    // 同名的东西是个文件：不能当目录用，也不能默默把条目倒进父目录
                    !existing.isFolder -> throw ArchiveOpenException(
                        "目标位置上已经有一个同名文件，不能当解压目录；选「自动改名」或先把它挪开",
                    )
                }
                // 落点始终是那个文件夹：新建与"合并进已有"都一样。
                // createdFolder 只标记"本次新建"，供一键清理与导航判断用。
                dest = target
            }
            // 包里哪些目录落不下去（撞上库里同名的**文件**）：整棵子树都要跳过。
            // 只跳目录那一条不够 —— 它下面的文件照样会在 ensureFolderPath 上抛异常，把整包打断。
            val blocked = blockedDirs(dest, all)
            for (item in all.filter { it.isDirectory }) {
                if (item.displayName in blocked) continue
                repo.ensureFolderPath(joinRelative(dest, item.displayName))
            }
            val files = all.filter { !it.isDirectory }
            attempted = files.size
            val total = reader.totalBytes(all)
            val links = files.filterNot { it.regularFile }
            tally.skippedLinks = links.size
            val writable = files.filter { it.regularFile }.filter { item ->
                val parent = item.displayName.substringBeforeLast('/', "")
                if (parent in blocked) {
                    tally.skipped++
                    if (tally.lastRefusal == null) {
                        tally.lastRefusal = "包内目录「$parent」和库里一个同名文件撞上了，它下面的条目没有解压"
                    }
                    false
                } else {
                    true
                }
            }
            var writtenLocally = 0L
            try {
                reader.forEachContent(writable) { item, stream ->
                    currentCoroutineContext().ensureActive()
                    val id = writeEntry(item, stream, dest, policy, tally, ids, budget)
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
            refusalNote = tally.lastRefusal,
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
                writeEntry(entry, stream, dest, policy, tally, ids, ExtractBudget.forVault())
            }
        }
        // 被体积上限挡下时不静默返回 null：单条目提取要让用户看到到底是为什么
        if (ids.isEmpty()) tally.lastRefusal?.let { throw ArchiveOpenException(it) }
        ids.firstOrNull()?.let { repo.entryById(it) }
    }

    /**
     * 把包内一个条目解到缓存里的临时文件，给预览用（不进库、不建索引）。
     * 落在 cacheDir/entry-preview/ 这个专用子目录，由 [clearPreviewCache] 统一回收，
     * 不然每预览一次就在缓存里留一个没人管的大文件。
     */
    suspend fun extractToTemp(
        archive: File,
        entry: ArchiveItem,
        password: String?,
        targetNameHint: String,
    ): File? = withContext(Dispatchers.IO) {
        requireReadable(archive)
        if (!entry.regularFile) return@withContext null
        val suffix = targetNameHint.substringAfterLast('.', "")
        val dir = repo.previewCacheDir()
        val temp = File(dir, "preview-${System.nanoTime()}${if (suffix.isEmpty()) "" else ".$suffix"}")
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
            // 取消时上下文已经不可用，挂起函数会在入口就抛，.part 反而清不掉
            withContext(NonCancellable) { repo.discardAllocation(allocation) }
            throw e
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 落点里哪些"包内目录"不能拿来放文件：目录条目本身撞上库里一个同名的**文件**，
     * 或者路径中间某一级在库里是个文件。按不同的父目录各查一次，不按条目数查。
     */
    private suspend fun blockedDirs(dest: String, items: List<ArchiveItem>): Set<String> {
        val candidates = items.map { it.displayName.substringBeforeLast('/', "") } +
            items.filter { it.isDirectory }.map { it.displayName }
        val blocked = HashSet<String>()
        candidates.filter { it.isNotEmpty() }.distinct().forEach { parent ->
            var prefix = ""
            for (segment in parent.split('/').filter { it.isNotEmpty() }) {
                prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
                val found = repo.entryAt(joinRelative(dest, prefix))
                if (found != null && !found.isFolder) {
                    blocked += parent
                    break
                }
            }
        }
        return blocked
    }

    /** 单个条目落到库里；返回新条目 id，跳过时返回 null。整包与单条提取共用这一段。 */
    private suspend fun writeEntry(
        item: ArchiveItem,
        stream: java.io.InputStream,
        dest: String,
        policy: ConflictPolicy,
        tally: Tally,
        createdIds: MutableList<Long>,
        budget: ExtractBudget,
    ): Long? {
        // 包内声明的大小可能造假或压根没有，超限的条目不写；整包解压时只跳过这一条
        budget.refuseEntryBeforeWrite(item.sizeBytes)?.let {
            tally.skipped++
            tally.lastRefusal = it
            return null
        }
        val rel = joinRelative(dest, item.displayName)
        val dirRel = rel.substringBeforeLast('/', "")
        val leafName = rel.substringAfterLast('/')
        val existing = repo.entryAt(rel)
        // 计数留到真的落地之后再加，否则写失败的条目也被算成"改名/覆盖成功"
        var renamedInto = false
        var trashedId: Long? = null
        if (existing != null) {
            when (policy) {
                ConflictPolicy.SKIP -> {
                    tally.skipped++
                    return null
                }
                // 覆盖目录太危险（会连带删掉整棵子树），退化成另存一份
                ConflictPolicy.OVERWRITE -> if (existing.isFolder) {
                    renamedInto = true
                } else {
                    // 移进回收站而不是彻底删掉：万一只删未写就失败，原文件还回得来
                    repo.trash(listOf(existing.id))
                    trashedId = existing.id
                }
                ConflictPolicy.RENAME -> renamedInto = true
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
                    // 声明大小不可信的包（gz / bz2 / xz）只能靠边写边查拦住
                    budget.watch(read.toLong()) { repo.usableBytes() }?.let { throw ArchiveOpenException(it) }
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
            if (renamedInto) tally.renamed++
            if (trashedId != null) tally.overwritten++
            committed.id
        } catch (e: Exception) {
            // 取消时上下文已不可用，直接调用挂起的 discardAllocation 会在入口就抛：
            // .part 清不掉，还会顶掉真正的失败原因
            withContext(NonCancellable) { repo.discardAllocation(allocation) }
            // 取消要原样传出去，不能被包装成"解压失败"
            if (trashedId != null && e !is kotlinx.coroutines.CancellationException) {
                throw ArchiveOpenException("${e.messageSafe()}；被替换的原文件已移入回收站，可以在回收站还原")
            }
            throw e
        }
    }

    private class Tally {
        var written = 0
        var renamed = 0
        var overwritten = 0
        var skipped = 0
        var skippedLinks = 0
        /** 最近一次因超限被拒的原因，单条目提取要把它原样报给界面。 */
        var lastRefusal: String? = null
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

    /** 清掉包内预览留下的缓存文件（不属于库内容，也不进索引）。 */
    suspend fun clearPreviewCache() = withContext(Dispatchers.IO) {
        repo.previewCacheDir().listFiles()?.forEach { file -> runCatching { file.delete() } }
        Unit
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val MAX_RENAME_ATTEMPTS = 500
    }
}
