package com.qoder.pocketvault.support

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64

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
