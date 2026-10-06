package com.qoder.pocketvault

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qoder.pocketvault.data.ImportProgress
import com.qoder.pocketvault.data.db.ListOptions
import com.qoder.pocketvault.support.FakeSaf
import com.qoder.pocketvault.support.FakeTreeBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导入链路里那几条"会丢数据"的验收，跑在真实 Android 运行时 + 真实 Room + 真实私有目录上。
 * 来源换成内存里的假 DocumentsProvider（[com.qoder.pocketvault.support.FakeSafProvider]），
 * 被测代码走的仍是真实的 cr.query / openInputStream / DocumentsContract.deleteDocument。
 */
@RunWith(AndroidJUnit4::class)
class ImportSafetyTest {

    private lateinit var context: Context
    private lateinit var graph: AppGraph
    private lateinit var saf: FakeSaf

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        graph = context.appGraph()
        saf = FakeSaf(context.contentResolver)
    }

    @After
    fun tearDown() {
        runCatching { saf.reset() }
    }

    private fun awaitDone(timeoutMs: Long = 30_000): ImportProgress {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (graph.importer.progress.value.active && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(
            "导入在 ${timeoutMs}ms 内没结束：${graph.importer.progress.value}",
            graph.importer.progress.value.active,
        )
        return graph.importer.progress.value
    }

    private suspend fun namesIn(dir: String): List<String> =
        graph.repo.observeFolder(dir, ListOptions()).first().map { it.name }

    /** 验收 1：0 字节文件必须正常入库，而不是被谎报成"已存在"。 */
    @Test
    fun zeroByteFilesAreImported(): Unit = runBlocking {
        val tree = FakeTreeBuilder("safeImportA")
        tree.file("notes.txt", "第一轮同步内容\n".toByteArray())
        tree.file("empty.txt", ByteArray(0))
        val sub = tree.dir("sub")
        tree.file("log.txt", ByteArray(0), sub)
        tree.file("data.csv", "a,b,c\n".toByteArray(), sub)
        saf.seed(tree)

        graph.importer.importTree(tree.treeUri(), "", rememberFolder = false, deleteSource = false)
        val progress = awaitDone()

        assertEquals("一个都不该失败：${progress.message} / ${saf.log().describe()}", 0, progress.failed)
        assertEquals("一个都不该跳过：${progress.message} / ${saf.log().describe()}", 0, progress.skipped)

        val empty = graph.repo.entryAt("safeImportA/empty.txt")
        assertNotNull("0 字节文件没入库；目录内容=${namesIn("safeImportA")}", empty)
        assertEquals(0L, empty!!.sizeBytes)
        val physical = graph.repo.physicalFile(empty)
        assertTrue("索引有记录但磁盘上没有：$physical", physical.isFile)
        assertEquals(0L, physical.length())

        assertNotNull("子目录里的 0 字节文件也要入库", graph.repo.entryAt("safeImportA/sub/log.txt"))
        assertEquals(listOf("empty.txt", "notes.txt", "sub"), namesIn("safeImportA").sorted())
        assertEquals(listOf("data.csv", "log.txt"), namesIn("safeImportA/sub").sorted())
    }

    /** 验收 2：导入 → 移入回收站 → 反复重新同步，库里只能有一份 ACTIVE。 */
    @Test
    fun repeatedSyncDoesNotAccumulateCopies(): Unit = runBlocking {
        val tree = FakeTreeBuilder("resyncB")
        tree.file("a.txt", "AAA".toByteArray())
        tree.file("empty.txt", ByteArray(0))
        saf.seed(tree)

        graph.importer.importTree(tree.treeUri(), "", rememberFolder = false, deleteSource = false)
        awaitDone()

        val doomed = graph.repo.entryAt("resyncB/empty.txt")
        assertNotNull("先决条件没成立：第一次同步没把 empty.txt 弄进库", doomed)
        graph.repo.trash(listOf(doomed!!.id))

        repeat(3) {
            graph.importer.importTree(tree.treeUri(), "", rememberFolder = false, deleteSource = false)
            awaitDone()
        }

        assertEquals(
            "反复同步累积了副本（旧 bug：bySignature 没有 state 过滤 + 进回收站没清签名）：" +
                namesIn("resyncB"),
            1,
            namesIn("resyncB").count { it == "empty.txt" },
        )
        assertEquals(
            "回收站里那份不该被反复复制",
            1,
            graph.repo.observeTrash().first().count { it.name == "empty.txt" },
        )
        assertEquals(1, namesIn("resyncB").count { it == "a.txt" })
    }

    /**
     * 验收 3：provider 中途报错 ≠ 目录已空。这是最伤用户数据的一条——
     * 旧实现把读失败当空目录，于是"移动文件夹（删除源文件）"会删掉一个非空的源目录。
     */
    @Test
    fun unreadableSourceDirectoryIsNeverDeleted() {
        val tree = FakeTreeBuilder("dangerC")
        tree.file("top.txt", "顶层文件".toByteArray())
        val full = tree.dir("full")
        tree.file("hidden.txt", "里面还有东西".toByteArray(), full)
        // 第一次列 full 就失败：遍历跳过它，prune 再查还是失败
        tree.failAfter(full, after = 0)
        saf.seed(tree)

        graph.importer.importTree(tree.treeUri(), "", rememberFolder = false, deleteSource = true)
        val progress = awaitDone()
        val log = saf.log()

        // 正向对照：删除链路本身是通的，否则下面那条"没删"就成了空断言
        assertTrue(
            "假提供器没收到任何删除请求，说明 deleteDocument 没走预期的路，" +
                "负向断言因此不成立：${log.describe()}",
            log.attempts.isNotEmpty(),
        )
        assertEquals("被删的只该是那个已搬空的文件：${log.describe()}", listOf("top.txt"), log.deleted)

        // 这就是 P0-3 的要害：读不到的目录一次都不该被请求删除
        assertFalse("读失败的目录被当成空目录送去删除了：${log.describe()}", log.attempts.contains(full))
        assertTrue(
            "结果里要说明为什么没删：${progress.message}",
            progress.message?.contains("为安全没有删除") == true,
        )
    }

    /**
     * P1-10：两个不同来源、同名同字节的文件都得入库。
     * 旧签名只算 name+size，第二个会被静默丢掉。
     */
    @Test
    fun sameNameSameSizeFromDifferentSourcesBothImport(): Unit = runBlocking {
        val tree = FakeTreeBuilder("pickDsrc")
        val boxA = tree.dir("boxA")
        val boxB = tree.dir("boxB")
        val bytes = "identical payload".toByteArray()
        tree.file("dup.txt", bytes, boxA)
        tree.file("dup.txt", bytes, boxB)
        saf.seed(tree)
        val uris = listOf(tree.docUri("$boxA/dup.txt"), tree.docUri("$boxB/dup.txt"))

        graph.importer.importFiles(uris, "pickD", deleteSource = false)
        val progress = awaitDone()

        assertEquals(
            "第二个同名同大小的文件被弱签名误判成重复了：${progress.message} / ${saf.log().describe()}",
            0,
            progress.skipped,
        )
        assertEquals(0, progress.failed)
        val inLib = namesIn("pickD")
        assertEquals("两份都该在库里（同名会自动改名，但不该丢）：$inLib", 2, inLib.count { it.startsWith("dup") })
    }
}
