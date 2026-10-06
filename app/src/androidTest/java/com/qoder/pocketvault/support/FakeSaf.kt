package com.qoder.pocketvault.support

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** 假 SAF 来源里的一个节点。bytes 给空数组就是在造 0 字节文件。 */
private class Node(
    val docId: String,
    val parent: String,
    val name: String,
    val isDir: Boolean,
    val bytes: ByteArray,
) {
    var deleted = false

    /** mtime 必须稳定：树路径的签名里带 mtime，每次查询都返回当前时刻的话，重新同步永远判不出重复。 */
    val modifiedAt: Long = STABLE_TIME + (docId.hashCode().toLong() and 0xFFFFL)

    companion object {
        const val STABLE_TIME = 1_700_000_000_000L
    }
}

private class Fail(val docId: String, val after: Int) {
    var hits = 0
}

/**
 * 内存里的假"源目录树"。
 *
 * 为什么是普通 [ContentProvider] 而不是 [android.provider.DocumentsProvider]：
 * 系统对 DocumentsProvider 有硬性要求 —— `attachInfo` 里会检查它是否由
 * `android.permission.MANAGE_DOCUMENTS` 保护，没有就直接
 * `SecurityException: Provider must be protected by MANAGE_DOCUMENTS`（CI 上实测崩在这里），
 * 而本应用刻意不申请任何权限，也拿不到那个签名级权限。
 * 普通 provider 没有这条约束；`DocumentsContract` 的 `isDocumentUri` 走的是纯 URI 形状判断
 * （`/tree/<root>/document/<id>`），deleteDocument 则会退到 `ContentResolver.call(...)`，
 * 所以我们只要按同样的 URI 形状应答，被测代码走的仍是真实调用链。
 *
 * 另外 androidTest 的 provider 属于另一个包，可能在独立进程，所以**静态对象不能当通信信道**：
 * 树与删除轨迹都存在 provider 里，测试用 `call("fake-seed"/"fake-log"/"fake-reset")` 播种与取回。
 */
class FakeSafProvider : ContentProvider() {

    private val nodes = LinkedHashMap<String, Node>()
    private val failures = mutableListOf<Fail>()
    private val seen = mutableListOf<String>()
    private val deleteAttempts = mutableListOf<String>()
    private val deleteRequests = mutableListOf<String>()

    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    override fun onCreate(): Boolean = true

    // ------------------------------------------------------------ URI 形状

    /** [Pair] 的 second 表示这是"列子项"还是"看单个文档"。 */
    private fun parse(uri: Uri): Pair<String, Boolean>? {
        val parts = uri.pathSegments
        if (parts.isEmpty() || parts[0] != "tree") return null
        return when {
            parts.size >= 4 && parts[parts.size - 2] == "children" -> Uri.decode(parts.last()) to true
            parts.size >= 4 && parts[parts.size - 2] == "document" -> Uri.decode(parts.last()) to false
            parts.size == 2 -> parts[1] to true          // /tree/<rootId>：把根当父目录
            else -> null
        }
    }

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

    override fun getType(uri: Uri): String? {
        val parsed = parse(uri) ?: return null
        val node = nodes[parsed.first] ?: return null
        return if (node.isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        seen += "query:$uri"
        val parsed = parse(uri) ?: return null
        val (docId, isChildren) = parsed
        if (isChildren) {
            failures.firstOrNull { it.docId == docId }?.let { fail ->
                fail.hits++
                if (fail.hits > fail.after) {
                    // 真实 provider 在权限被回收 / 瞬时 IO 出错时就是这样抛的
                    throw IOException("simulated provider failure on $docId")
                }
            }
            return cursorOf(liveChildren(docId))
        }
        val node = nodes[docId]?.takeUnless { it.deleted } ?: return null
        return cursorOf(listOf(node))
    }

    /** 只给 URI，所以把字节写成真实临时文件再交出去；读链路和真 provider 一样过磁盘。 */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        seen += "openFile:$uri"
        val docId = parse(uri)?.first ?: throw FileNotFoundException("读不了：$uri")
        val node = nodes[docId]?.takeUnless { it.deleted } ?: throw FileNotFoundException("没有这个文件：$docId")
        if (node.isDir) throw FileNotFoundException("目录读不了：$docId")
        val host = context ?: throw IllegalStateException("provider 还没 attachInfo")
        val dir = File(host.filesDir, "fakesrc").apply { mkdirs() }
        val file = File(dir, "${docId.hashCode().toUInt()}.bin")
        file.writeBytes(node.bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        openFile(uri, mode)

    // ------------------------------------------------------------ 控制口 + 删除拦截

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_SEED -> seed(extras)
        METHOD_LOG -> log()
        METHOD_RESET -> {
            clear()
            Bundle()
        }

        METHOD_DELETE -> {
            // 一次 CI 就能看出这一版 AOSP 把 docId 放在哪儿：arg 还是某个 extras key
            seen += "delete-raw:arg=<$arg> keys=${extras?.keySet()?.sorted()?.joinToString()}"
            performDelete(arg, extras)
            Bundle()
        }

        else -> Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "未知方法：$method") }
    }

    /** 万一这一版走的是 CRUD 式删除，也照样记账。 */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        seen += "delete:$uri"
        performDelete(parse(uri)?.first, null)
        return 0
    }

    private fun performDelete(docId: String?, extras: Bundle?) {
        val id = docId ?: extras?.getString(KEY_DOC_ID) ?: extras?.getString(KEY_DOC_ID_ALT) ?: "unknown"
        deleteAttempts += id
        val node = nodes[id]
        if (node == null) {
            seen += "delete-missing:$id"
            return
        }
        if (node.isDir && liveChildren(node.docId).isNotEmpty()) {
            // 真实提供器对非空目录也是这么拒绝的；更要紧的是应用压根不该走到这里
            throw IOException("目录非空，拒绝删除：${node.docId}")
        }
        node.deleted = true
        deleteRequests += id
    }

    private fun clear() {
        nodes.clear()
        failures.clear()
        seen.clear()
        deleteAttempts.clear()
        deleteRequests.clear()
    }

    private fun seed(extras: Bundle?): Bundle {
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

    // ------------------------------------------------------------ 其余都是占位

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.qoder.pocketvault.testdocs"

        /** DocumentsContract 内部用同一个字面量（那个常量本身是 @hide，编译期引用不到）。 */
        private const val METHOD_DELETE = "delete_document"

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
        const val KEY_DOC_ID = "document_id"
        const val KEY_DOC_ID_ALT = "android:document_id"

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
class FakeSaf(private val resolver: android.content.ContentResolver) {

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
