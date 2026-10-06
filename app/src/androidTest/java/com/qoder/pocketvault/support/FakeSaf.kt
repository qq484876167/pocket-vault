package com.qoder.pocketvault.support

import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 内存里的假"源目录树"，跑在真的 DocumentsProvider 里。
 *
 * 三点设计约束：
 * 1. ContentResolver 的 query / openInputStream / call 全是 final，造不了假 resolver；
 *    DocumentsProvider 留给子类的是按 docId 的回调，URI 形状由框架解析，
 *    于是被测代码走的仍是真实的 cr.query / openInputStream / DocumentsContract.deleteDocument。
 * 2. androidTest 的 provider 属于另一个包，可能落在独立进程，所以**静态对象不能当通信信道**：
 *    树和删除轨迹都存在 provider 里，测试用 call("fake-seed" / "fake-log" / "fake-reset")
 *    播种与取回，跨进程也成立。
 * 3. mtime 必须稳定：树路径的签名里带 mtime，每次查询都返回当前时刻的话
 *    "重新同步同一目录"永远判不出重复，用例就测不到 P0-2。
 */
class FakeSafProvider : DocumentsProvider() {

    private class Node(
        val docId: String,
        val parent: String,
        val name: String,
        val isDir: Boolean,
        val bytes: ByteArray,
    ) {
        var deleted = false
        val modifiedAt: Long = STABLE_TIME + (docId.hashCode().toLong() and 0xFFFFL)
    }

    private class Fail(val docId: String, val after: Int) {
        var hits = 0
    }

    private val nodes = LinkedHashMap<String, Node>()
    private val failures = mutableListOf<Fail>()
    private val seen = mutableListOf<String>()
    private val deleteAttempts = mutableListOf<String>()
    private val deleteRequests = mutableListOf<String>()

    override fun onCreate(): Boolean = true

    // ------------------------------------------------------------ 测试控制口

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_SEED -> seed(extras) ?: Bundle()
        METHOD_LOG -> log()
        METHOD_RESET -> {
            clear()
            Bundle()
        }
        // 框架的 delete_document 等照常走 DocumentsProvider 的分发
        else -> super.call(method, arg, extras)
    }

    private fun clear() {
        nodes.clear()
        failures.clear()
        seen.clear()
        deleteAttempts.clear()
        deleteRequests.clear()
    }

    private fun seed(extras: Bundle?): Bundle? {
        clear()
        val docIds = extras?.getStringArrayList(KEY_DOC_IDS) ?: return Bundle()
        val parents = extras.getStringArrayList(KEY_PARENTS) ?: arrayListOf()
        val names = extras.getStringArrayList(KEY_NAMES) ?: arrayListOf()
        val dirs = extras.getStringArrayList(KEY_DIRS) ?: arrayListOf()
        val payload = extras.getStringArrayList(KEY_B64) ?: arrayListOf()
        docIds.forEachIndexed { i, docId ->
            nodes[docId] = Node(
                docId = docId,
                parent = parents.getOrNull(i).orEmpty(),
                name = names.getOrNull(i) ?: docId,
                isDir = dirs.getOrNull(i) == "1",
                bytes = payload.getOrNull(i)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
            )
        }
        val failIds = extras.getStringArrayList(KEY_FAIL_IDS) ?: arrayListOf()
        val failAfter = extras.getIntegerArrayList(KEY_FAIL_AFTER) ?: arrayListOf()
        failIds.forEachIndexed { i, id -> failures += Fail(id, failAfter.getOrNull(i) ?: 0) }
        return Bundle()
    }

    private fun log(): Bundle = Bundle().apply {
        putStringArrayList(KEY_ATTEMPTS, ArrayList(deleteAttempts))
        putStringArrayList(KEY_DELETED, ArrayList(deleteRequests))
        putStringArrayList(KEY_SEEN, ArrayList(seen.take(80)))
    }

    // ------------------------------------------------------------ DocumentsProvider

    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private fun row(node: Node) = arrayOf<Any?>(
        node.docId,
        node.name,
        if (node.isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
        node.bytes.size.toLong(),
        node.modifiedAt,
    )

    private fun cursorOf(list: List<Node>) =
        MatrixCursor(columns, list.size).also { cursor -> list.forEach { cursor.addRow(row(it)) } }

    private fun liveChildren(parentDocId: String): List<Node> =
        nodes.values.filter { it.parent == parentDocId && !it.deleted }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(
        arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID),
        1,
    ).apply { addRow(arrayOf<Any?>("fake", nodes.keys.firstOrNull() ?: "fake")) }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        seen += "queryDocument:$documentId"
        val node = nodes[documentId]?.takeUnless { it.deleted }
            ?: throw FileNotFoundException("没有这个节点：$documentId")
        return cursorOf(listOf(node))
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        seen += "queryChildDocuments:$parentDocumentId"
        failures.firstOrNull { it.docId == parentDocumentId }?.let { fail ->
            fail.hits++
            if (fail.hits > fail.after) {
                // 真实 provider 在权限被回收 / 瞬时 IO 出错时就是这样抛的
                throw IOException("simulated provider failure on $parentDocumentId")
            }
        }
        return cursorOf(liveChildren(parentDocumentId))
    }

    override fun getDocumentType(documentId: String): String {
        val node = nodes[documentId] ?: throw FileNotFoundException("没有这个节点：$documentId")
        return if (node.isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
    }

    /** 只给 docId，所以把字节写成真实临时文件再交出去；读链路和真 provider 一样过磁盘。 */
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        seen += "openDocument:$documentId"
        val node = nodes[documentId]?.takeUnless { it.deleted }
            ?: throw FileNotFoundException("没有这个文件：$documentId")
        if (node.isDir) throw FileNotFoundException("目录读不了：$documentId")
        val host = context ?: throw IllegalStateException("provider 还没 attachInfo")
        val dir = File(host.filesDir, "fakesrc").apply { mkdirs() }
        val file = File(dir, "${documentId.hashCode().toUInt()}.bin")
        file.writeBytes(node.bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun deleteDocument(documentId: String) {
        deleteAttempts += documentId
        seen += "deleteDocument:$documentId"
        val node = nodes[documentId] ?: throw FileNotFoundException("没有这个节点：$documentId")
        if (node.isDir && liveChildren(node.docId).isNotEmpty()) {
            // 真实提供器对非空目录也是这么拒绝的；更要紧的是应用压根不该走到这里
            throw IOException("目录非空，拒绝删除：${node.docId}")
        }
        node.deleted = true
        deleteRequests += documentId
    }

    companion object {
        const val AUTHORITY = "com.qoder.pocketvault.testdocs"
        private const val STABLE_TIME = 1_700_000_000_000L

        const val METHOD_SEED = "fake-seed"
        const val METHOD_LOG = "fake-log"
        const val METHOD_RESET = "fake-reset"

        const val KEY_DOC_IDS = "docIds"
        const val KEY_PARENTS = "parents"
        const val KEY_NAMES = "names"
        const val KEY_DIRS = "dirs"
        const val KEY_B64 = "b64"
        const val KEY_FAIL_IDS = "failDocIds"
        const val KEY_FAIL_AFTER = "failAfter"

        const val KEY_ATTEMPTS = "attempts"
        const val KEY_DELETED = "deleted"
        const val KEY_SEEN = "seen"
    }
}

/** 一棵要播给假提供器的树。docId 用 父/名 的形式，和真提供器一样带层级。 */
class FakeTreeBuilder(val root: String) {

    data class Node(
        val docId: String,
        val parent: String,
        val name: String,
        val isDir: Boolean,
        val bytes: ByteArray,
    )

    val nodes = mutableListOf(Node(root, "", root, isDir = true, ByteArray(0)))
    val failing = linkedMapOf<String, Int>()

    private fun idOf(name: String, parent: String) = if (parent == root) name else "$parent/$name"

    fun dir(name: String, parent: String = root): String {
        val id = idOf(name, parent)
        nodes += Node(id, parent, name, true, ByteArray(0))
        return id
    }

    fun file(name: String, bytes: ByteArray, parent: String = root): String {
        val id = idOf(name, parent)
        nodes += Node(id, parent, name, false, bytes)
        return id
    }

    /** 这个父目录的"列子项"在成功 [after] 次之后开始抛异常。 */
    fun failAfter(docId: String, after: Int) {
        failing[docId] = after
    }

    fun treeUri(): Uri = DocumentsContract.buildTreeDocumentUri(FakeSafProvider.AUTHORITY, root)

    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri(), docId)
}

/** 测试侧的假 SAF 控制口：播种、取删除轨迹、清空。全走 call，跨进程也一样。 */
class FakeSaf(private val resolver: ContentResolver) {

    private val control = Uri.parse("content://${FakeSafProvider.AUTHORITY}/ctl")

    data class Log(val attempts: List<String>, val deleted: List<String>, val seen: List<String>) {
        fun describe() = "attempts=$attempts deleted=$deleted seen=$seen"
    }

    fun seed(tree: FakeTreeBuilder) {
        val extras = Bundle().apply {
            putStringArrayList(FakeSafProvider.KEY_DOC_IDS, ArrayList(tree.nodes.map { it.docId }))
            putStringArrayList(FakeSafProvider.KEY_PARENTS, ArrayList(tree.nodes.map { it.parent }))
            putStringArrayList(FakeSafProvider.KEY_NAMES, ArrayList(tree.nodes.map { it.name }))
            putStringArrayList(FakeSafProvider.KEY_DIRS, ArrayList(tree.nodes.map { if (it.isDir) "1" else "0" }))
            putStringArrayList(
                FakeSafProvider.KEY_B64,
                ArrayList(tree.nodes.map { Base64.encodeToString(it.bytes, Base64.NO_WRAP) }),
            )
            putStringArrayList(FakeSafProvider.KEY_FAIL_IDS, ArrayList(tree.failing.keys))
            putIntegerArrayList(FakeSafProvider.KEY_FAIL_AFTER, ArrayList(tree.failing.values))
        }
        resolver.call(control, FakeSafProvider.METHOD_SEED, null, extras)
    }

    fun log(): Log =
        resolver.call(control, FakeSafProvider.METHOD_LOG, null, null).let { b ->
            Log(
                attempts = b?.getStringArrayList(FakeSafProvider.KEY_ATTEMPTS)?.toList() ?: emptyList(),
                deleted = b?.getStringArrayList(FakeSafProvider.KEY_DELETED)?.toList() ?: emptyList(),
                seen = b?.getStringArrayList(FakeSafProvider.KEY_SEEN)?.toList() ?: emptyList(),
            )
        }

    fun reset() {
        resolver.call(control, FakeSafProvider.METHOD_RESET, null, null)
    }
}
