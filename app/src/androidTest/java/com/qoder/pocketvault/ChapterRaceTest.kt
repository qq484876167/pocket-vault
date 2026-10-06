package com.qoder.pocketvault

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qoder.pocketvault.support.FakeSaf
import com.qoder.pocketvault.support.FakeTreeBuilder
import com.qoder.pocketvault.ui.viewer.ViewerViewModel
import com.qoder.pocketvault.util.TextBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验收 6：快速连点两个大小差很多的章节，列表内容必须还是最后选的那一章，进度也不能记串。
 *
 * P0-6 的竞态就在 `selectChapter -> showChapter`：旧实现里 `chapterJob?.cancel()` 只把 Job
 * 标记为取消，而 `TextBook.paragraphsOf` 内部没有任何挂起点，大章照样跑完并把结果写进
 * StateFlow —— 于是"界面显示 A 章的段落、_chapterIndex 已经是 B 章"，
 * 随后 onParagraphRead 还会把 A 章的段落号当 B 章的进度写进库。
 */
@RunWith(AndroidJUnit4::class)
class ChapterRaceTest {

    private lateinit var context: Context
    private lateinit var graph: AppGraph
    private lateinit var saf: FakeSaf

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        graph = context.appGraph()
        saf = FakeSaf(context.contentResolver)
    }

    private suspend fun importNovel(root: String, bytes: ByteArray): Long {
        val tree = FakeTreeBuilder(root)
        tree.file("novel.txt", bytes)
        saf.seed(tree)
        graph.importer.importTree(tree.treeUri(), "", rememberFolder = false, deleteSource = false)
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (graph.importer.progress.value.active && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        val entry = graph.repo.entryAt("$root/novel.txt")
        assertNotNull("小说没进库：${graph.importer.progress.value}", entry)
        return entry!!.id
    }

    private suspend fun awaitBook(vm: ViewerViewModel): TextBook {
        var waited = 0
        while (vm.book.value == null && waited < 120_000) {
            delay(50)
            waited += 50
        }
        val book = vm.book.value
        assertNotNull("TextBook 没建起来（ready=${vm.ready.value}，error=${vm.bookError.value}）", book)
        return book!!
    }

    private fun novelBytes(): ByteArray = buildString {
        append("第一章 大山\n")
        repeat(24_000) { i ->
            append("这是超长正文的第").append(i).append("行，专门用来让这一章的加载真的花点时间。\n")
        }
        append("第二章 小水\n短句甲在这里\n短句乙在这里\n短句丙在这里\n")
    }.toByteArray()

    /**
     * 小 → 大 → 小 连着点：中间那一大章一定会"迟到"。
     * 起始章节是第一章，所以第一下点第二章是有效跳转（同章早退，不会把竞态窗口抹掉）。
     */
    @Test
    fun lateBigChapterLoadCannotOverwriteSmallChapter(): Unit = runBlocking {
        val entryId = importNovel("raceBook", novelBytes())
        val vm = ViewerViewModel(graph, entryId)
        val book = awaitBook(vm)
        assertEquals("该识别出两个章节：${book.chapters.map { it.title }}", 2, book.chapters.size)

        vm.selectChapter(1)
        vm.selectChapter(0)
        vm.selectChapter(1)

        // 给旧 bug 留出"大章后到并覆盖"的时间窗
        delay(6_000)
        assertEquals(1, vm.chapterIndex.value)
        val shown = vm.paragraphs.value
        assertTrue("段落列表空着", shown.isNotEmpty())
        assertTrue(
            "界面显示的不是当前章节（小章）的段落：${shown.take(3)}",
            shown.any { it.contains("短句甲在这里") },
        )
        assertTrue(
            "大章段落被迟到的那次加载覆盖了：${shown.take(2)}",
            shown.none { it.contains("专门用来让这一章的加载真的花点时间") },
        )

        // 进度不能把大章的段落号当小章的写进库
        val smallParagraphs = book.paragraphsOf(1).size
        val saved = graph.repo.readingProgress(entryId)
        assertTrue(
            "进度记到了小章不存在的段落：$saved（小章一共 $smallParagraphs 段）",
            saved == null || saved.paragraphIndex < smallParagraphs,
        )
    }

    /** 反过来：大 → 小 也应该停在小章（这是最直觉的"先点后大再点小的"点法）。 */
    @Test
    fun bigThenSmallAlsoSettlesOnSmall(): Unit = runBlocking {
        val entryId = importNovel("raceBook2", novelBytes())
        val vm = ViewerViewModel(graph, entryId)
        val book = awaitBook(vm)
        assertEquals(2, book.chapters.size)

        // 起始就是第一章，先跳走再跳回来，保证两次都真的触发了加载
        vm.selectChapter(1)
        delay(200)
        vm.selectChapter(0)
        delay(200)
        vm.selectChapter(1)
        delay(6_000)

        assertEquals(1, vm.chapterIndex.value)
        val shown = vm.paragraphs.value
        assertTrue(
            "最后选的是小章，列表里却混进了大章正文：${shown.take(3)}",
            shown.any { it.contains("短句甲在这里") } &&
                shown.none { it.contains("专门用来让这一章的加载真的花点时间") },
        )
    }
}
