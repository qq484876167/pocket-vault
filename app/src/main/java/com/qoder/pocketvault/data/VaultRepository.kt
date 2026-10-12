package com.qoder.pocketvault.data

import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.MovePlanner
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.core.VaultPrefs
import com.qoder.pocketvault.core.dedupeRelative
import com.qoder.pocketvault.core.joinRelative
import com.qoder.pocketvault.core.parentRelative
import com.qoder.pocketvault.data.db.EntryState
import com.qoder.pocketvault.data.db.KindStat
import com.qoder.pocketvault.data.db.PocketVaultDb
import com.qoder.pocketvault.data.db.ROOT_ID
import com.qoder.pocketvault.data.db.TRASH_PARENT_ID
import com.qoder.pocketvault.data.db.TRASH_PATH_PREFIX
import com.qoder.pocketvault.data.db.VaultDao
import com.qoder.pocketvault.data.db.VaultEntry
import com.qoder.pocketvault.data.db.ListOptions
import com.qoder.pocketvault.data.db.BookStat
import com.qoder.pocketvault.data.db.DayStat
import com.qoder.pocketvault.data.db.MediaPosition
import com.qoder.pocketvault.data.db.PdfBookmark
import com.qoder.pocketvault.data.db.ReadingStat
import com.qoder.pocketvault.data.db.SortField
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.time.LocalDate
import java.util.Locale

/** 导入前的落位信息：唯一逻辑路径 + 正式文件 + 临时文件。 */
data class Allocation(
    val parentId: Long,
    val name: String,
    val relativePath: String,
    val finalFile: File,
    val tempFile: File,
)

data class StorageReport(
    val perKind: List<KindStat>,
    val usedBytes: Long,
    val freeBytes: Long,
    val fileCount: Int,
)

/**
 * 阅读统计页要的四个数：总共读了多久、读过几天、按书排行、最近若干天各读了多久。
 * 天数与总时长都由 SQL 聚合出来，界面不再自己拼。
 */
data class ReadingSummary(
    val totalSeconds: Int = 0,
    val readDays: Int = 0,
    val perBook: List<BookStat> = emptyList(),
    val days: List<DayStat> = emptyList(),
)

/** 排行里的一行：书名 + 累计时长 + 推进了多少处。 */
data class ReadingRank(
    val label: String,
    val seconds: Int,
    val blocks: Int,
)

/** 目录选择器里的一行：路径、显示名、直接子项数。 */
data class FolderRow(
    val relativePath: String,
    val name: String,
    val childCount: Int,
)

/**
 * 库内移动的结果。[carried] 是"随所在文件夹一起走"的项数，界面必须说出来，
 * 否则用户会以为少搬了几个。[alreadyThere] 是本来就在目标目录里的项。
 */
data class MoveSummary(
    val moved: Int,
    val carried: Int,
    val alreadyThere: Int,
)

/**
 * 文件库的唯一写入口。约定：
 *  - 相对路径只用于 ACTIVE 条目；TRASHED 条目的磁盘位置由 [physicalFile] 推导。
 *  - 目录结构以 parentId 链为准，relativePath 是它的缓存视图，任何改动都通过 rebuildPaths 重算。
 *  - 方法直接抛出带中文信息的异常，由 ViewModel 层转成界面提示。
 */
class VaultRepository(
    private val db: PocketVaultDb,
    private val paths: VaultPaths,
    private val prefs: VaultPrefs,
) {
    private val dao: VaultDao = db.vaultDao()
    private val collator: Collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.TERTIARY }

    // ---------------------------------------------------------------- 观察

    fun observeFolder(dirRelativePath: String, options: ListOptions): Flow<List<VaultEntry>> = flow {
        val parentId = ensureFolderPath(dirRelativePath)
        emitAll(dao.observeChildren(parentId))
    }.map { sortEntries(it, options) }.flowOn(Dispatchers.IO)

    fun observeRecent(limit: Int = 400): Flow<List<VaultEntry>> =
        dao.observeRecent(limit).flowOn(Dispatchers.IO)

    fun observeByKind(kind: FileKind, limit: Int = 2000): Flow<List<VaultEntry>> =
        dao.observeByKind(kind, limit).map { entries ->
            sortEntries(entries, ListOptions(SortField.MODIFIED, ascending = false))
        }.flowOn(Dispatchers.IO)

    fun observeFavorites(): Flow<List<VaultEntry>> =
        dao.observeFavorites().map { sortEntries(it, ListOptions(SortField.MODIFIED, false)) }.flowOn(Dispatchers.IO)

    fun observeTrash(): Flow<List<VaultEntry>> = dao.observeTrash().flowOn(Dispatchers.IO)

    fun observeStats(): Flow<StorageReport> = dao.observeStats().map { stats ->
        withContext(Dispatchers.IO) {
            StorageReport(
                perKind = stats.sortedByDescending { it.totalBytes },
                usedBytes = dao.usedBytes(),
                freeBytes = paths.usableBytes(),
                fileCount = dao.activeFileCount(),
            )
        }
    }.flowOn(Dispatchers.IO)

    /** 私有目录还能写多少字节；拿不到返回 0，调用方按"未知"处理不误报。 */
    fun usableBytes(): Long = paths.usableBytes()

    /** 包内条目预览的缓存子目录：派生数据，可随时整目录清空。 */
    fun previewCacheDir(): File = paths.previewCacheDir

    // ---------------------------------------------------------------- 目录与分配

    /** 保证逻辑目录存在（磁盘 + 索引），返回其条目 id。'' 代表根目录。 */
    suspend fun ensureFolderPath(dirRelativePath: String): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val clean = VaultPaths.normalizeRelative(dirRelativePath)
            if (clean.isEmpty()) return@withTransaction ROOT_ID
            var parentId = ROOT_ID
            val built = StringBuilder()
            for (segment in clean.split('/')) {
                if (built.isNotEmpty()) built.append('/')
                built.append(segment)
                val rel = built.toString()
                val existing = dao.byPath(rel)
                if (existing != null) {
                    if (existing.state == EntryState.TRASHED) {
                        // 回收站条目不占用逻辑路径（见 trash 的重命名规则），走到这里说明数据异常。
                        throw IllegalStateException("索引状态异常：$rel")
                    }
                    if (!existing.isFolder) throw IllegalStateException("同路径上已有同名文件：$rel")
                    parentId = existing.id
                    continue
                }
                val dir = paths.fileOf(rel)
                if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                    throw IllegalStateException("无法创建目录：$rel")
                }
                val now = System.currentTimeMillis()
                parentId = dao.insert(
                    VaultEntry(
                        relativePath = rel,
                        name = segment,
                        parentId = parentId,
                        kind = FileKind.FOLDER,
                        mimeType = null,
                        sizeBytes = 0L,
                        modifiedAtMillis = now,
                        importedAtMillis = now,
                        sourceSignature = null,
                    )
                )
            }
            parentId
        }
    }

    suspend fun entryById(id: Long): VaultEntry? = withContext(Dispatchers.IO) { dao.byId(id) }

    /**
     * 某个目录条目的真实路径；条目不存在、已进回收站、或者根本不是目录时返回 null。
     *
     * 需要"某项在哪个目录里"的地方一律用它，别再拿 `relativePath` 截字符串——
     * relativePath 是 parentId 链的缓存视图（见本类顶部约定），两者一旦不同步，
     * 截出来的落点会把解压/复制的文件写到别处去。
     */
    suspend fun folderPathById(id: Long): String? = withContext(Dispatchers.IO) {
        dao.byId(id)?.takeIf { it.state == EntryState.ACTIVE && it.isFolder }?.relativePath
    }

    suspend fun entriesByIds(ids: List<Long>): List<VaultEntry> = withContext(Dispatchers.IO) {
        dao.byIds(ids)
    }

    suspend fun folderPaths(): List<String> = withContext(Dispatchers.IO) { dao.folderPaths() }

    /**
     * 目录选择器用的一层数据：某个目录的**直接子目录**，每行带自己的子项数。
     * 计数走一次 `parentId IN (…) GROUP BY` 聚合，不逐行数（200 个子目录就是 200 次查询）。
     * 返回 null 表示这个目录已经不存在了——历史路径里点到失效条目时靠它给提示。
     */
    suspend fun folderRows(dirRelativePath: String): List<FolderRow>? = withContext(Dispatchers.IO) {
        val clean = VaultPaths.normalizeRelative(dirRelativePath)
        val parent = if (clean.isEmpty()) null else dao.byPath(clean)?.takeIf {
            it.state == EntryState.ACTIVE && it.kind == FileKind.FOLDER
        }
        if (clean.isNotEmpty() && parent == null) return@withContext null
        val kids = sortEntries(
            dao.childFolders(parent?.id ?: ROOT_ID),
            ListOptions(SortField.NAME, ascending = true),
        )
        if (kids.isEmpty()) return@withContext emptyList()
        val counts = dao.childCounts(kids.map { it.id }).associate { it.parent to it.total }
        kids.map { FolderRow(joinRelative(clean, it.name), it.name, counts[it.id] ?: 0) }
    }

    /** ACTIVE 子项（索引顺序，未做中文排序）。 */
    suspend fun childrenOf(entry: VaultEntry): List<VaultEntry> = withContext(Dispatchers.IO) { dao.children(entry.id) }

    /** 生成未被占用的名字并准备好 .part 临时文件（导入用）。 */
    suspend fun allocateFile(dirRelativePath: String, rawName: String): Allocation = withContext(Dispatchers.IO) {
        db.withTransaction {
            val parentId = ensureFolderPath(dirRelativePath)
            val dirRel = VaultPaths.normalizeRelative(dirRelativePath)
            val name = VaultPaths.sanitizeName(rawName)
            val taken = dao.children(parentId).map { it.name }.toHashSet()
            val unique = dedupeRelative(dirRel, name, taken.map { joinRelative(dirRel, it) }.toSet())
            val finalName = unique.substringAfterLast('/')
            val finalFile = paths.fileOf(unique)
            finalFile.parentFile?.mkdirs()
            Allocation(
                parentId = parentId,
                name = finalName,
                relativePath = unique,
                finalFile = finalFile,
                tempFile = File(finalFile.parentFile, finalFile.name + ".part"),
            )
        }
    }

    /** 写入完成：临时文件原子改名，读元数据，落索引。 */
    suspend fun commitFile(
        allocation: Allocation,
        sourceSignature: String?,
        sourceModifiedAt: Long?,
        declaredSize: Long?,
        mimeType: String?,
    ): VaultEntry = withContext(Dispatchers.IO) {
        val temp = allocation.tempFile
        val target = allocation.finalFile
        if (!temp.exists()) throw IllegalStateException("临时文件丢失：${allocation.name}")
        if (target.exists()) throw IllegalStateException("目标文件已存在：${allocation.relativePath}")
        if (!temp.renameTo(target)) throw IllegalStateException("无法保存文件：${allocation.name}")
        val kind = FileKind.classify(allocation.name, mimeType)
        val size = target.length().let { if (it > 0 || declaredSize == null) it else declaredSize }
        val meta = MediaMetadataProbe.probe(target, kind)
        val now = System.currentTimeMillis()
        val entry = VaultEntry(
            relativePath = allocation.relativePath,
            name = allocation.name,
            parentId = allocation.parentId,
            kind = kind,
            mimeType = mimeType ?: FileKind.guessMime(allocation.name),
            sizeBytes = size,
            modifiedAtMillis = sourceModifiedAt?.takeIf { it > 0 } ?: now,
            importedAtMillis = now,
            sourceSignature = sourceSignature,
            width = meta.width,
            height = meta.height,
            durationMs = meta.durationMs,
        )
        db.withTransaction {
            entry.copy(id = dao.insert(entry))
        }
    }

    suspend fun discardAllocation(allocation: Allocation) = withContext(Dispatchers.IO) {
        runCatching { allocation.tempFile.delete() }
        Unit
    }

    suspend fun findBySignature(signature: String): VaultEntry? = withContext(Dispatchers.IO) {
        dao.bySignature(signature)
    }

    /**
     * 按逻辑路径查条目，用于解压时的同名冲突判断与"目标文件夹是否已存在"。
     * 回收站里的条目不占逻辑路径，所以只认 ACTIVE 状态。
     */
    suspend fun entryAt(relativePath: String): VaultEntry? = withContext(Dispatchers.IO) {
        val clean = VaultPaths.normalizeRelative(relativePath)
        if (clean.isEmpty()) null else dao.byPath(clean)?.takeIf { it.state == EntryState.ACTIVE }
    }

    // ---------------------------------------------------------------- 基本操作

    suspend fun createFolder(dirRelativePath: String, rawName: String): VaultEntry = withContext(Dispatchers.IO) {
        db.withTransaction {
            val parentId = ensureFolderPath(dirRelativePath)
            val dirRel = VaultPaths.normalizeRelative(dirRelativePath)
            val name = VaultPaths.sanitizeName(rawName)
            val taken = dao.children(parentId).map { joinRelative(dirRel, it.name) }.toSet()
            val rel = dedupeRelative(dirRel, name, taken)
            val folder = paths.fileOf(rel)
            if (!folder.mkdir() && !folder.isDirectory) throw IllegalStateException("无法创建文件夹：$name")
            val now = System.currentTimeMillis()
            val id = dao.insert(
                VaultEntry(
                    relativePath = rel,
                    name = name,
                    parentId = parentId,
                    kind = FileKind.FOLDER,
                    mimeType = null,
                    sizeBytes = 0L,
                    modifiedAtMillis = now,
                    importedAtMillis = now,
                    sourceSignature = null,
                )
            )
            requireNotNull(dao.byId(id))
        }
    }

    suspend fun rename(id: Long, rawNewName: String): VaultEntry = withContext(Dispatchers.IO) {
        db.withTransaction {
            val entry = requireNotNull(dao.byId(id)) { "文件不存在" }
            check(entry.state == EntryState.ACTIVE) { "回收站中的项目不能重命名，请先恢复" }
            val newName = VaultPaths.sanitizeName(rawNewName)
            if (newName == entry.name) return@withTransaction entry
            val siblings = dao.children(entry.parentId).filter { it.id != id }.map { it.name }
            check(newName !in siblings) { "同级已存在同名项目：$newName" }
            val newRel = joinRelative(parentRelative(entry.relativePath), newName)
            val dest = paths.fileOf(newRel)
            check(!dest.exists()) { "目标位置已有同名文件" }
            val source = physicalFile(entry)
            if (!source.renameTo(dest)) throw IllegalStateException("重命名失败：${entry.name}")
            val updated = entry.copy(
                name = newName,
                relativePath = newRel,
                kind = if (entry.isFolder) FileKind.FOLDER else FileKind.classify(newName, entry.mimeType),
                mimeType = if (entry.isFolder) null else (entry.mimeType ?: FileKind.guessMime(newName)),
            )
            dao.update(updated)
            rebuildPaths(updated.id, newRel)
            requireNotNull(dao.byId(id))
        }
    }

    /**
     * 库内移动。三条硬规矩（都是被真实坏数据逼出来的）：
     *  1. 决策全部在 [MovePlanner] 里算完，**非法目标抛出时磁盘一点没动**；
     *  2. 勾了父文件夹又勾它里面的子文件夹时，子项随父项走，不再单独 rename 一遍旧路径
     *     （旧实现会抛异常，索引回滚而物理文件已经搬走，留下管不到的游离数据）；
     *  3. 循环里每项都重新按 id 查当前状态与路径，不信任开工前那份快照。
     */
    suspend fun move(ids: List<Long>, destDirRelativePath: String): MoveSummary = withContext(Dispatchers.IO) {
        db.withTransaction {
            val destRel = VaultPaths.normalizeRelative(destDirRelativePath)
            val requested = dao.byIds(ids).filter { it.state == EntryState.ACTIVE }
            if (requested.isEmpty()) return@withTransaction MoveSummary(0, 0, 0)

            // 目标目录此刻可能还不存在：不存在时它不可能在谁肚子里，路径检查仍然要跑。
            val destId = if (destRel.isEmpty()) ROOT_ID else dao.byPath(destRel)?.id
            // 祖先链一次查好再交给 MovePlanner： planner 是纯函数（探针直接在 JVM 上跑），
            // 不能在里面挂 suspend 查询。
            val chains = HashMap<Long, List<Long>>()
            requested.forEach { chains[it.id] = ancestorChain(it.id) }
            if (destId != null && destId != ROOT_ID) chains[destId] = ancestorChain(destId)
            val plan = MovePlanner.plan(
                selected = requested.map { MovePlanner.Node(it.id, it.parentId, it.relativePath, it.isFolder) },
                destRelativePath = destRel,
                destId = destId,
                ancestorIds = { id -> chains[id].orEmpty() },
            )
            plan.illegal.firstOrNull()?.let { throw IllegalStateException(it) }

            // 预检：所有实体文件都还在，才允许动第一块砖。
            // 真正的坑是"搬成一半才失败"——事务只能回滚索引，renameTo 搬走的文件回不去，
            // 于是索引说它还在原位（点开显示文件不存在）、新位置那份成了库里管不到的游离数据。
            val targets = ArrayList<VaultEntry>()
            val missing = ArrayList<String>()
            for (node in plan.moveNow) {
                val entry = dao.byId(node.id) ?: continue
                if (entry.state != EntryState.ACTIVE) continue
                if (destId != null && entry.parentId == destId) continue   // 已经在目标目录里
                if (!physicalFile(entry).exists()) missing += entry.name
                if (missing.isEmpty()) targets += entry
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException("实体文件已经不在了：${missing.joinToString("、")}")
            }

            // 校验通过之后才允许建目录：非法目标不该在库里留下一个空文件夹。
            val destParentId = ensureFolderPath(destRel)
            val occupied = dao.children(destParentId).map { joinRelative(destRel, it.name) }.toHashSet()
            val finished = ArrayList<Triple<String, String, Long>>()    // 旧绝对路径 -> 新绝对路径（+ 条目 id）
            var done = 0
            try {
                for (entry in targets) {
                    val fresh = dao.byId(entry.id) ?: continue
                    if (fresh.state != EntryState.ACTIVE) continue
                    val rel = dedupeRelative(destRel, fresh.name, occupied)
                    val newName = rel.substringAfterLast('/')
                    val source = physicalFile(fresh)
                    val dest = paths.fileOf(rel)
                    if (!source.exists()) throw IllegalStateException("找不到要移动的文件：${fresh.name}")
                    dest.parentFile?.mkdirs()
                    if (!source.renameTo(dest)) throw IllegalStateException("移动失败：${fresh.name}")
                    finished += Triple(source.absolutePath, dest.absolutePath, fresh.id)
                    dao.update(fresh.copy(parentId = destParentId, relativePath = rel, name = newName))
                    rebuildPaths(fresh.id, rel)
                    occupied.add(rel)
                    done++
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 把已经搬成功的按原路搬回去（倒着来，先搬的不被后搬的挡住）。
                // 事务回滚会把索引恢复成开工前的样子，所以磁盘必须跟着回去，两边才又对得上。
                val leftovers = ArrayList<String>()
                finished.asReversed().forEach { (from, to, _) ->
                    val current = File(to)
                    if (current.exists() && !current.renameTo(File(from))) leftovers += current.name
                }
                val note = if (leftovers.isEmpty()) {
                    "已把前 $done 项放回原位，整批没有移动"
                } else {
                    "有 ${leftovers.size} 项没能放回原位（${leftovers.joinToString("、")}），" +
                        "请到设置页「重建索引（磁盘与数据库对账）」纠正"
                }
                throw IllegalStateException("${e.message}；$note", e)
            }
            MoveSummary(moved = done, carried = plan.carried.size, alreadyThere = plan.alreadyThere.size)
        }
    }

    /**
     * 某个条目从**父亲**开始向上的 id 链（不含自己，遇根或断链即止）。
     * [MAX_TRAVERSAL] 是死循环保险：数据被改坏成 A→B→A 时不能把调用方挂死。
     */
    private suspend fun ancestorChain(id: Long): List<Long> {
        val chain = ArrayList<Long>()
        var guard = 0
        var current = dao.byId(id)?.parentId
        while (current != null && current != ROOT_ID && guard++ < MAX_TRAVERSAL) {
            chain.add(current)
            current = dao.byId(current)?.parentId
        }
        return chain
    }

    /** 复制一份到目标目录，磁盘与索引一起深拷贝。 */
    suspend fun copyInto(ids: List<Long>, destDirRelativePath: String): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            val destParentId = ensureFolderPath(destDirRelativePath)
            val destRel = VaultPaths.normalizeRelative(destDirRelativePath)
            var copied = 0
            for (id in ids) {
                val entry = dao.byId(id) ?: continue
                check(entry.state == EntryState.ACTIVE) { "回收站中的项目不能复制" }
                copied += cloneSubtree(entry, destParentId, destRel, depth = 0)
            }
            copied
        }
    }

    suspend fun trash(ids: List<Long>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            for (id in ids) {
                val entry = dao.byId(id) ?: continue
                if (entry.state == EntryState.TRASHED) continue
                val trashFile = File(paths.trashDir, entry.id.toString())
                paths.trashDir.mkdirs()
                val source = physicalFile(entry)
                if (source.exists() && !source.renameTo(trashFile)) {
                    throw IllegalStateException("移入回收站失败：${entry.name}")
                }
                val now = System.currentTimeMillis()
                dao.update(
                    entry.copy(
                        state = EntryState.TRASHED,
                        parentId = TRASH_PARENT_ID,
                        trashOriginParentId = entry.parentId,
                        trashedAtMillis = now,
                        trashedVia = entry.id,
                        // 判重只针对在用条目：进回收站就把签名让出来，
                        // 不然"删掉再同步同一目录"会因为这条残留签名判重失效
                        sourceSignature = null,
                        relativePath = "$TRASH_PATH_PREFIX/${entry.id}/${entry.relativePath}",
                    )
                )
                stampSubtreeTrash(entry.id, entry.id, entry.relativePath, now)
            }
        }
    }

    suspend fun restore(ids: List<Long>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            for (id in ids) {
                val requested = dao.byId(id) ?: continue
                if (requested.state != EntryState.TRASHED) continue
                // 随父目录一起进回收站的后代，恢复时要整棵子树一起回到磁盘上。
                val viaId = requested.trashedVia ?: requested.id
                val entry = if (viaId == requested.id) requested else (dao.byId(viaId) ?: continue)
                if (entry.state != EntryState.TRASHED) continue
                val originId = entry.trashOriginParentId?.takeIf { parentId ->
                    dao.byId(parentId)?.let { it.state == EntryState.ACTIVE && it.isFolder } == true
                } ?: ROOT_ID
                val originRel = if (originId == ROOT_ID) "" else requireNotNull(dao.byId(originId)).relativePath
                val taken = dao.children(originId).map { joinRelative(originRel, it.name) }.toSet()
                val rel = dedupeRelative(originRel, entry.name, taken)
                val dest = paths.fileOf(rel)
                dest.parentFile?.mkdirs()
                val trashFile = File(paths.trashDir, viaId.toString())
                if (!trashFile.exists()) {
                    // 垃圾桶里那份实体文件已经没了（手工删了或被系统清掉）：
                    // 继续写 ACTIVE 只会留下一个"点开即失败"的幽灵条目，索引永远对不上磁盘
                    throw IllegalStateException("回收站里的文件已经不在了，无法恢复：${entry.name}")
                }
                if (!trashFile.renameTo(dest)) {
                    throw IllegalStateException("恢复失败：${entry.name}")
                }
                dao.update(
                    entry.copy(
                        state = EntryState.ACTIVE,
                        parentId = originId,
                        relativePath = rel,
                        name = rel.substringAfterLast('/'),
                        trashedAtMillis = null,
                        trashedVia = null,
                        trashOriginParentId = null,
                    )
                )
                reviveSubtree(viaId, entry.id)
                rebuildPaths(entry.id, rel)
            }
        }
    }

    /** 彻底删除（回收站里的条目）。 */
    suspend fun deleteForever(ids: List<Long>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            for (id in ids) {
                val entry = dao.byId(id) ?: continue
                check(entry.state == EntryState.TRASHED) { "请先移入回收站" }
                val viaId = entry.trashedVia ?: entry.id
                deleteSubtreeRows(viaId)
                runCatching { File(paths.trashDir, viaId.toString()).deleteRecursively() }
            }
        }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        db.withTransaction {
            val vias = dao.trashEntries().map { it.trashedVia ?: it.id }.distinct()
            for (viaId in vias) {
                deleteSubtreeRows(viaId)
                runCatching { File(paths.trashDir, viaId.toString()).deleteRecursively() }
            }
        }
    }

    suspend fun purgeExpiredTrash() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - prefs.trashRetentionDays * 24L * 3600 * 1000
        db.withTransaction {
            val roots = dao.expiredTrash(cutoff).map { it.trashedVia ?: it.id }.distinct()
            for (viaId in roots) {
                deleteSubtreeRows(viaId)
                runCatching { File(paths.trashDir, viaId.toString()).deleteRecursively() }
            }
        }
    }

    /**
     * 从文件库彻底移除（不进回收站）。只应在"已成功复制到别处并校验过字节数"之后调用，
     * 因此这里不做任何回收站兜底。
     */
    suspend fun deletePermanently(ids: List<Long>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            for (id in ids) {
                val entry = dao.byId(id) ?: continue
                runCatching { physicalFile(entry).deleteRecursively() }
                val doomed = (collectDescendantIds(entry.id) + entry.id).distinct()
                dao.deleteByIds(doomed)
                db.purgeAccountRows(doomed)
            }
        }
    }

    private suspend fun collectDescendantIds(rootId: Long): List<Long> {
        val out = ArrayList<Long>()
        val queue = ArrayDeque<Long>()
        queue.addLast(rootId)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_TRAVERSAL) {
            val current = queue.removeFirst()
            for (child in dao.childrenAnyState(current)) {
                out += child.id
                queue.addLast(child.id)
            }
        }
        return out
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = withContext(Dispatchers.IO) { dao.setFavorite(id, favorite) }

    /**
     * 按名称搜索。[withinRelativePath] 为空时是全库搜索；给定时只搜该目录及其**所有子目录**
     * ——relativePath 是 parentId 链的缓存视图，所以一次前缀匹配就能覆盖整棵子树。
     */
    suspend fun search(
        term: String,
        kind: FileKind?,
        withinRelativePath: String? = null,
        limit: Int = 400,
    ): List<VaultEntry> = withContext(Dispatchers.IO) {
        val pattern = escapeLike(term.trim())
        if (pattern.isEmpty()) return@withContext emptyList()
        val scope = withinRelativePath?.takeIf { it.isNotBlank() }?.let { it.trim('/') + "/" }
        dao.search(pattern, kind, scope, limit)
    }

    /**
     * 目录当前路径对应的行 id。搜索范围要靠 id 在路由里传，
     * 路径是用户任意起的名字（可能含 % # ? /），拼进 URI 再解出来容易出错。
     */
    suspend fun folderIdFor(relativePath: String): Long = withContext(Dispatchers.IO) {
        val clean = VaultPaths.normalizeRelative(relativePath)
        if (clean.isEmpty()) ROOT_ID else dao.byPath(clean)?.id ?: ROOT_ID
    }

    /**
     * 应用内查看时的浏览序列：同一文件夹内同一类型的全部条目，按中文拼音排序。
     * 只有图片与视频给序列（PDF / 文档单独打开），且保证被点开的条目一定在里面。
     */
    suspend fun viewerSequence(entry: VaultEntry): List<VaultEntry> = withContext(Dispatchers.IO) {
        val sequenceKinds = setOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO)
        if (entry.isFolder || entry.kind !in sequenceKinds) {
            return@withContext listOf(entry)
        }
        val siblings = dao.siblingsOfKind(entry.parentId, entry.kind)
        val merged = if (siblings.any { it.id == entry.id }) siblings else siblings + entry
        merged.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) { dao.rowCount() == 0 }

    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { dao.usedBytes() }

    // ---------------------------------------------------------------- 阅读进度

    suspend fun readingProgress(entryId: Long): com.qoder.pocketvault.data.db.ReaderProgress? =
        withContext(Dispatchers.IO) { db.readingProgressDao().find(entryId) }

    suspend fun saveReadingProgress(entryId: Long, chapter: Int, paragraph: Int, fontSize: Float) =
        withContext(Dispatchers.IO) {
            db.readingProgressDao().save(
                com.qoder.pocketvault.data.db.ReaderProgress(
                    entryId = entryId,
                    chapterIndex = chapter,
                    paragraphIndex = paragraph,
                    fontSize = fontSize,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

    suspend fun bookmarks(entryId: Long): List<com.qoder.pocketvault.data.db.ReaderBookmark> =
        withContext(Dispatchers.IO) { db.readingBookmarkDao().forEntry(entryId) }

    suspend fun addBookmark(entryId: Long, chapter: Int, paragraph: Int, label: String) =
        withContext(Dispatchers.IO) {
            db.readingBookmarkDao().save(
                com.qoder.pocketvault.data.db.ReaderBookmark(
                    entryId = entryId,
                    chapterIndex = chapter,
                    paragraphIndex = paragraph,
                    label = label,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }

    suspend fun removeBookmark(entryId: Long, chapter: Int, paragraph: Int) =
        withContext(Dispatchers.IO) { db.readingBookmarkDao().delete(entryId, chapter, paragraph) }

    // ------------------------------------------------------ 播放位置 / PDF 书签 / 阅读统计

    /**
     * 视频、音频看到第几毫秒。进度按 entryId 存，而 entryId 在移动 / 重命名后不变，
     * 所以换个目录回来照样接得上。
     */
    suspend fun saveMediaPosition(entryId: Long, positionMillis: Long, durationMillis: Long) =
        withContext(Dispatchers.IO) {
            db.mediaPositionDao().save(
                MediaPosition(
                    entryId = entryId,
                    positionMillis = positionMillis.coerceAtLeast(0L),
                    durationMillis = durationMillis.coerceAtLeast(0L),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

    suspend fun mediaPosition(entryId: Long): MediaPosition? =
        withContext(Dispatchers.IO) { db.mediaPositionDao().byEntry(entryId) }

    /** 「从头播」就是把这条记录抹掉，而不是写一个 0 进去让下次照样跳 0。 */
    suspend fun clearMediaPosition(entryId: Long) =
        withContext(Dispatchers.IO) { db.mediaPositionDao().clear(entryId) }

    suspend fun pdfBookmarks(entryId: Long): List<PdfBookmark> =
        withContext(Dispatchers.IO) { db.pdfBookmarkDao().forEntry(entryId) }

    /** 同一页重复加书签只刷新标签，不会堆出重复行（主键就是 entryId + 页码）。 */
    suspend fun addPdfBookmark(entryId: Long, page: Int, label: String) =
        withContext(Dispatchers.IO) {
            db.pdfBookmarkDao().save(
                PdfBookmark(
                    entryId = entryId,
                    pageIndex = page,
                    label = label,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }

    suspend fun removePdfBookmark(entryId: Long, page: Int) =
        withContext(Dispatchers.IO) { db.pdfBookmarkDao().delete(entryId, page) }

    /**
     * 把一段阅读时长累计到"这本书 · 今天"这一格。
     * 写盘的节奏由调用方掌握（切章、退出页面时才调），不能每秒都来一次——
     * 那是往 flash 上写无意义的频繁小事务。
     */
    suspend fun addReadingTime(entryId: Long, seconds: Int, blocks: Int) = withContext(Dispatchers.IO) {
        if (seconds <= 0 && blocks <= 0) return@withContext
        val day = LocalDate.now().toString()
        val statDao = db.readingStatDao()
        val current = statDao.byEntryDay(entryId, day)
        statDao.save(
            ReadingStat(
                entryId = entryId,
                day = day,
                seconds = (current?.seconds ?: 0) + seconds,
                blocks = (current?.blocks ?: 0) + blocks,
            )
        )
    }

    /** 统计页一次读全，别在界面上逐本书拼查询。 */
    suspend fun readingSummary(recentDays: Int = 60): ReadingSummary = withContext(Dispatchers.IO) {
        val statDao = db.readingStatDao()
        ReadingSummary(
            totalSeconds = statDao.totalSeconds(),
            readDays = statDao.activeDays(),
            perBook = statDao.perBook(),
            days = statDao.recentDays(recentDays),
        )
    }

    /**
     * 读书排行，带上书名。
     * 条目可能已经被彻底删除（账是账、书是书）：那种行标成"（已移除）"而不是悄悄消失，
     * 否则用户会觉得"统计对不上"。
     */
    suspend fun readingRanks(limit: Int = 8): List<ReadingRank> = withContext(Dispatchers.IO) {
        db.readingStatDao().perBook().take(limit).map { stat ->
            val entry = dao.byId(stat.entryId)
            ReadingRank(
                label = entry?.name ?: "（已移除）",
                seconds = stat.seconds,
                blocks = stat.blocks,
            )
        }
    }

    // ---------------------------------------------------------------- 辅助

    fun physicalFile(entry: VaultEntry): File = when (entry.state) {
        EntryState.ACTIVE -> paths.fileOf(entry.relativePath)
        EntryState.TRASHED -> File(paths.trashDir, (entry.trashedVia ?: entry.id).toString())
    }

    fun paths(): VaultPaths = paths

    private suspend fun stampSubtreeTrash(rootRowId: Long, viaId: Long, rootRel: String, now: Long) {
        val queue = ArrayDeque<Long>()
        queue.addLast(rootRowId)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_TRAVERSAL) {
            val current = queue.removeFirst()
            for (child in dao.childrenAnyState(current)) {
                val newRel = if (child.relativePath.startsWith("$rootRel/")) {
                    "$TRASH_PATH_PREFIX/$viaId/${child.relativePath}"
                } else {
                    "$TRASH_PATH_PREFIX/$viaId/${child.name}"
                }
                dao.update(
                    child.copy(
                        state = EntryState.TRASHED,
                        trashedAtMillis = now,
                        trashedVia = viaId,
                        trashOriginParentId = child.parentId,
                        relativePath = newRel,
                    )
                )
                queue.addLast(child.id)
            }
        }
    }

    private suspend fun reviveSubtree(viaId: Long, rootRowId: Long) {
        val queue = ArrayDeque<Long>()
        queue.addLast(rootRowId)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_TRAVERSAL) {
            val current = queue.removeFirst()
            for (child in dao.childrenAnyState(current)) {
                if (child.trashedVia == viaId && child.state == EntryState.TRASHED) {
                    dao.update(
                        child.copy(
                            state = EntryState.ACTIVE,
                            trashedAtMillis = null,
                            trashedVia = null,
                            trashOriginParentId = null,
                        )
                    )
                }
                queue.addLast(child.id)
            }
        }
    }

    private suspend fun deleteSubtreeRows(viaId: Long) {
        val doomed = ArrayList<Long>()
        val queue = ArrayDeque<Long>()
        queue.addLast(viaId)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_TRAVERSAL) {
            val current = queue.removeFirst()
            for (child in dao.childrenAnyState(current)) {
                doomed += child.id
                queue.addLast(child.id)
            }
        }
        doomed += viaId
        val ids = doomed.distinct()
        dao.deleteByIds(ids)
        db.purgeAccountRows(ids)
    }

    /** 依据 parentId 链重建子树的 relativePath（移动 / 恢复后调用）。 */
    private suspend fun rebuildPaths(rootId: Long, rootRelativePath: String) {
        val queue = ArrayDeque<Pair<Long, String>>()
        queue.addLast(rootId to rootRelativePath)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_TRAVERSAL) {
            val (parentId, parentRel) = queue.removeFirst()
            for (child in dao.childrenAnyState(parentId)) {
                if (child.state != EntryState.ACTIVE) continue
                val rel = joinRelative(parentRel, child.name)
                if (rel != child.relativePath) {
                    dao.update(child.copy(relativePath = rel))
                }
                queue.addLast(child.id to rel)
            }
        }
    }

    private suspend fun cloneSubtree(entry: VaultEntry, destParentId: Long, destRel: String, depth: Int): Int {
        require(depth < MAX_DEPTH) { "目录层级过深" }
        val taken = dao.children(destParentId).map { joinRelative(destRel, it.name) }.toSet()
        val rel = dedupeRelative(destRel, entry.name, taken)
        val name = rel.substringAfterLast('/')
        val now = System.currentTimeMillis()
        if (entry.isFolder) {
            val folder = paths.fileOf(rel)
            if (!folder.mkdir() && !folder.isDirectory) throw IllegalStateException("无法创建目录：$name")
            val newId = dao.insert(entry.copy(id = 0, parentId = destParentId, relativePath = rel, name = name, importedAtMillis = now, sourceSignature = null))
            var count = 1
            for (child in dao.children(entry.id)) {
                if (count > MAX_ITEMS) throw IllegalStateException("复制项目数量超出上限")
                count += cloneSubtree(child, newId, rel, depth + 1)
            }
            return count
        }
        val target = paths.fileOf(rel)
        target.parentFile?.mkdirs()
        val source = physicalFile(entry)
        runCatching { source.copyTo(target, overwrite = false) }
            .onFailure { throw IllegalStateException("复制失败：${entry.name}") }
        dao.insert(
            entry.copy(
                id = 0,
                parentId = destParentId,
                relativePath = rel,
                name = name,
                importedAtMillis = now,
                sourceSignature = null,
            )
        )
        return 1
    }

    private fun sortEntries(entries: List<VaultEntry>, options: ListOptions): List<VaultEntry> {
        val direction = if (options.ascending) 1 else -1
        val comparator = compareBy<VaultEntry> { if (it.isFolder) 0 else 1 }
            .thenComparator { a, b ->
                when (options.field) {
                    SortField.NAME -> collator.compare(a.name, b.name)
                    SortField.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                    SortField.MODIFIED -> a.modifiedAtMillis.compareTo(b.modifiedAtMillis)
                    SortField.IMPORTED -> a.importedAtMillis.compareTo(b.importedAtMillis)
                } * direction
            }
            .thenComparator { a, b -> collator.compare(a.name, b.name) }
            .thenComparator { a, b -> a.id.compareTo(b.id) }
        return entries.sortedWith(comparator)
    }

    private fun escapeLike(term: String): String =
        term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val MAX_TRAVERSAL = 200_000
        const val MAX_DEPTH = 40
        const val MAX_ITEMS = 50_000
    }
}
