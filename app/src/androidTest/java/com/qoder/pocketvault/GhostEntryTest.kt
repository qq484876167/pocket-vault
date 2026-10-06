package com.qoder.pocketvault

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qoder.pocketvault.data.db.EntryState
import com.qoder.pocketvault.data.db.ListOptions
import com.qoder.pocketvault.support.FakeSaf
import com.qoder.pocketvault.support.FakeTreeBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验收 8：垃圾桶里的实体文件被手工删掉之后再点"恢复"，
 * 必须报错或清掉记录，不能留下一个"索引说在、磁盘上没"的幽灵条目；
 * 而且对账（IndexVerifier）之后这条记录要真的消失。
 */
@RunWith(AndroidJUnit4::class)
class GhostEntryTest {

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

    private fun awaitDone(timeoutMs: Long = 30_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (graph.importer.progress.value.active && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertTrue("导入没结束：${graph.importer.progress.value}", !graph.importer.progress.value.active)
    }

    @Test
    fun restoreWithoutPhysicalFileFailsAndRescanDropsTheGhost(): Unit = runBlocking {
        val tree = FakeTreeBuilder("ghostDir")
        tree.file("ghost.txt", "这条记录会先活着".toByteArray())
        saf.seed(tree)
        graph.importer.importTree(tree.treeUri(), "", rememberFolder = false, deleteSource = false)
        awaitDone()

        val live = graph.repo.entryAt("ghostDir/ghost.txt")
        assertNotNull("前置条件：条目没进库", live)
        assertTrue("前置条件：磁盘上没有实体", graph.repo.physicalFile(live!!).isFile)

        graph.repo.trash(listOf(live.id))
        val trashed = graph.repo.entryById(live.id)!!
        assertEquals(EntryState.TRASHED, trashed.state)
        val trashFile = graph.repo.physicalFile(trashed)
        assertTrue("前置条件：回收站实体不存在：$trashFile", trashFile.isFile)

        // 模拟用户在系统设置里清存储 / adb 动过文件
        assertTrue("手工删不掉回收站实体：$trashFile", trashFile.delete())

        try {
            graph.repo.restore(listOf(live.id))
            fail("垃圾桶实体已不存在时 restore 应该抛异常，而不是静默写成 ACTIVE")
        } catch (e: IllegalStateException) {
            assertTrue(
                "报错文案要能说明原因：${e.message}",
                e.message?.contains("已经不在了") == true,
            )
        }

        // 关键：条目不能因为这次"失败的恢复"变成 ACTIVE 指向不存在的文件
        val after = graph.repo.entryById(live.id)!!
        assertEquals("恢复失败后必须还留在回收站（可继续还原或删除），不能变幽灵", EntryState.TRASHED, after.state)

        // 对账要能把"索引有、磁盘无"的条目清掉
        val report = graph.rescan()
        assertNull("对账后这条记录还在索引里：${report.summary}", graph.repo.entryById(live.id))
        val stillInTrash = graph.repo.observeTrash().first().map { it.name }
        assertTrue("回收站列表里还留着这条幽灵：$stillInTrash", stillInTrash.none { it == "ghost.txt" })

        // 顺带确认对账没把别的目录级记录扫丢
        val rootKids = graph.repo.observeFolder("", ListOptions()).first().map { it.name }
        assertTrue("ghostDir 这个文件夹还在：$rootKids", rootKids.contains("ghostDir"))
    }
}
