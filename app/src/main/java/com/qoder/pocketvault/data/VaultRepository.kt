package com.qoder.pocketvault.data

import com.qoder.pocketvault.core.FileKind
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

    suspend fun entriesByIds(ids: List<Long>): List<VaultEntry> = withContext(Dispatchers.IO) {
        dao.byIds(ids)
    }

    suspend fun folderPaths(): List<String> = withContext(Dispatchers.IO) { dao.folderPaths() }

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

    suspend fun move(ids: List<Long>, destDirRelativePath: String): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            val destParentId = ensureFolderPath(destDirRelativePath)
            val destRel = VaultPaths.normalizeRelative(destDirRelativePath)
            val moved = dao.byIds(ids).filter { it.state == EntryState.ACTIVE && it.parentId != destParentId }
            if (moved.isEmpty()) return@withTransaction 0
            val occupied = dao.children(destParentId).map { joinRelative(destRel, it.name) }.toHashSet()
            var done = 0
            for (entry in moved) {
                if (entry.isFolder && isDescendantOfFolder(destRel, entry.relativePath)) {
                    throw IllegalStateException("不能把文件夹移动到它自己里面")
                }
                val rel = dedupeRelative(destRel, entry.name, occupied)
                val newName = rel.substringAfterLast('/')
                val source = physicalFile(entry)
                val dest = paths.fileOf(rel)
                dest.parentFile?.mkdirs()
                if (!source.renameTo(dest)) throw IllegalStateException("移动失败：${entry.name}")
                dao.update(entry.copy(parentId = destParentId, relativePath = rel, name = newName))
                rebuildPaths(entry.id, rel)
                occupied.add(rel)
                done++
            }
            done
        }
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
                if (trashFile.exists() && !trashFile.renameTo(dest)) {
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
                val doomed = collectDescendantIds(entry.id) + entry.id
                runCatching { physicalFile(entry).deleteRecursively() }
                dao.deleteByIds(doomed.distinct())
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
        if (entry.isFolder || (entry.kind != FileKind.IMAGE && entry.kind != FileKind.VIDEO)) {
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

    // ---------------------------------------------------------------- 辅助

    fun physicalFile(entry: VaultEntry): File = when (entry.state) {
        EntryState.ACTIVE -> paths.fileOf(entry.relativePath)
        EntryState.TRASHED -> File(paths.trashDir, (entry.trashedVia ?: entry.id).toString())
    }

    fun paths(): VaultPaths = paths

    /** 目标目录是否位于该文件夹内部，防止把文件夹移动到自己里面。 */
    private fun isDescendantOfFolder(candidateDestRel: String, folderRel: String): Boolean {
        val dest = candidateDestRel.trim('/')
        val folder = folderRel.trim('/')
        return dest == folder || dest.startsWith("$folder/")
    }

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
        dao.deleteByIds(doomed.distinct())
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
