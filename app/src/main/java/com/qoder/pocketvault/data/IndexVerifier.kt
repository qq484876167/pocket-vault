package com.qoder.pocketvault.data

import android.util.Log
import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.data.db.EntryState
import com.qoder.pocketvault.data.db.PocketVaultDb
import com.qoder.pocketvault.data.db.ROOT_ID
import com.qoder.pocketvault.data.db.VaultEntry
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class VerifyReport(
    val adopted: Int,
    val dropped: Int,
    val cleanedParts: Int,
) {
    val summary: String
        get() = buildString {
            if (adopted > 0) append("补录 $adopted 项；")
            if (dropped > 0) append("清理 $dropped 项失效记录；")
            if (cleanedParts > 0) append("删除 $cleanedParts 个中断残留；")
        }.removeSuffix("；")
}

/**
 * 磁盘与索引的双向对账。外部情况（用户在系统设置里清理存储、adb 推送文件、
 * 导入过程中断电）会让二者不一致，这里把它们收敛回一致状态。
 */
class IndexVerifier(
    private val db: PocketVaultDb,
    private val paths: VaultPaths,
) {
    private val dao = db.vaultDao()

    suspend fun verify(): VerifyReport = withContext(Dispatchers.IO) {
        paths.ensureRoot()
        var cleaned = 0
        // 1. 中断的 .part 残留
        val parts = paths.root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()
        parts.forEach { if (it.delete()) cleaned++ }

        var adopted = 0
        var dropped = 0

        // 2. 磁盘上有、索引里没有 -> 补录
        val diskDirs = paths.root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            .orEmpty()
        for (dir in diskDirs) {
            adopted += adoptDirectory(dir, ROOT_ID, "")
        }

        // 3. 索引里有、磁盘上没有了 -> 删除记录（连同子树）
        db.withTransaction {
            val stale = ArrayList<Long>()
            for (folder in dao.allFolders()) {
                if (folder.state != EntryState.ACTIVE) continue
                if (!paths.fileOf(folder.relativePath).isDirectory) {
                    stale += collectSubtree(folder.id) + folder.id
                }
            }
            // 文件也得逐个核对：只查目录的话，"索引里有、磁盘上没这个文件"的幽灵条目
            // 永远发现不了（回收站实体被手工删掉后恢复失败就是这种），点开必然失败。
            var droppedFiles = 0
            for (file in dao.allActiveFiles()) {
                if (file.id in stale) continue
                // 判不出来（路径异常）时宁可留着，不误删用户的索引
                val missing = runCatching { !paths.fileOf(file.relativePath).isFile }.getOrDefault(false)
                if (missing) {
                    stale += file.id
                    droppedFiles++
                }
            }
            val doomed = stale.distinct()
            if (doomed.isNotEmpty()) {
                dropped += doomed.size
                dao.deleteByIds(doomed)
            }
        }

        VerifyReport(adopted = adopted, dropped = dropped, cleanedParts = cleaned)
    }

    private suspend fun adoptDirectory(dir: File, parentId: Long, relPrefix: String): Int {
        val rel = if (relPrefix.isEmpty()) dir.name else "$relPrefix/${dir.name}"
        var count = 0
        val existing = dao.byPath(rel)
        val folderId = when {
            existing == null -> {
                dao.insert(
                    VaultEntry(
                        relativePath = rel,
                        name = dir.name,
                        parentId = parentId,
                        kind = FileKind.FOLDER,
                        mimeType = null,
                        sizeBytes = 0L,
                        modifiedAtMillis = dir.lastModified(),
                        importedAtMillis = System.currentTimeMillis(),
                        sourceSignature = null,
                    )
                ).also { count++ }
            }

            existing.isFolder && existing.state == EntryState.ACTIVE -> existing.id
            else -> return 0
        }
        val children = dir.listFiles().orEmpty()
        for (child in children.sortedBy { it.name }) {
            if (child.name.endsWith(".part") || child.name.startsWith(".")) continue
            if (child.isDirectory) {
                count += adoptDirectory(child, folderId, rel)
                continue
            }
            val childRel = "$rel/${child.name}"
            if (dao.byPath(childRel) != null) continue
            val kind = FileKind.classify(child.name, null)
            val meta = MediaMetadataProbe.probe(child, kind)
            dao.insert(
                VaultEntry(
                    relativePath = childRel,
                    name = child.name,
                    parentId = folderId,
                    kind = kind,
                    mimeType = FileKind.guessMime(child.name),
                    sizeBytes = child.length(),
                    modifiedAtMillis = child.lastModified(),
                    importedAtMillis = System.currentTimeMillis(),
                    sourceSignature = null,
                    width = meta.width,
                    height = meta.height,
                    durationMs = meta.durationMs,
                )
            )
            count++
        }
        return count
    }

    private suspend fun collectSubtree(rootId: Long): List<Long> {
        val out = ArrayList<Long>()
        val queue = ArrayDeque<Long>()
        queue.addLast(rootId)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 100_000) {
            val current = queue.removeFirst()
            for (child in dao.childrenAnyState(current)) {
                out += child.id
                queue.addLast(child.id)
            }
        }
        return out
    }

    private companion object {
        const val TAG = "IndexVerifier"
    }
}
