package com.qoder.pocketvault

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qoder.pocketvault.data.db.EntryState
import com.qoder.pocketvault.data.db.ListOptions
import com.qoder.pocketvault.support.FakeSaf
import com.qoder.pocketvault.support.FakeTreeBuilder
import com.qoder.pocketvault.ui.MainActivity
import com.qoder.pocketvault.ui.components.BusyOverlay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 界面上那几条"点了没反应 / 点了就出事"的验收：
 * 5 能移动到库根、9 遮罩吃掉点击、10 多选返回只退多选、11 回收站不可逆操作先确认、14 设置页有回执。
 *
 * 真实 MainActivity + 真实导航 + 真实 Room；数据来源还是那台假 SAF 提供器。
 */
@RunWith(AndroidJUnit4::class)
class BrowseUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var graph: AppGraph
    private lateinit var saf: FakeSaf

    companion object {
        @BeforeClass
        @JvmStatic
        fun skipOnboarding() {
            // 引导页会在首次启动时挡住主界面，测试要的是主界面
            InstrumentationRegistry.getInstrumentation().targetContext.appGraph().prefs.onboarded = true
        }
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        graph = context.appGraph()
        saf = FakeSaf(context.contentResolver)
    }

    private fun awaitImport(timeoutMs: Long = 30_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (graph.importer.progress.value.active && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertTrue("导入没结束：${graph.importer.progress.value}", !graph.importer.progress.value.active)
    }

    private fun seedUiTree() {
        val tree = FakeTreeBuilder("uiSeed")
        val sub = tree.dir("nest")
        tree.file("mv.txt", "要挪到根目录的文件".toByteArray(), sub)
        tree.file("trashme.txt", "要被彻底删除的文件".toByteArray())
        saf.seed(tree)
        graph.importer.importTree(tree.treeUri(), "", rememberFolder = false, deleteSource = false)
        awaitImport()
    }

    private suspend fun namesAt(dir: String): List<String> =
        graph.repo.observeFolder(dir, ListOptions()).first().map { it.name }

    private fun nodeExists(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** 多选条上的动作；动作被收进"更多操作"时先展开。 */
    private fun tapAction(label: String) {
        rule.waitForIdle()
        if (!nodeExists(label)) {
            rule.onNodeWithContentDescription("更多操作").performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithText(label).performClick()
        rule.waitForIdle()
    }

    /** 验收 5：文件在子目录里时，库根必须是可选目标，移动真的落到库根。 */
    @Test
    fun canMoveFileUpToVaultRoot() = runBlocking {
        seedUiTree()
        assertNotNull("前置条件：mv.txt 应该在 uiSeed/nest 里", graph.repo.entryAt("uiSeed/nest/mv.txt"))

        rule.onNodeWithText("mv.txt").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("已选 1 项").assertIsDisplayed()

        tapAction("移动到…")
        rule.onNodeWithText("选择目标位置").assertIsDisplayed()

        // 旧 bug：currentDir 恒为 ""，根目录被当成非法目标，按钮永远是灰的
        val confirm = rule.onNodeWithText("移动到这里")
        confirm.assertIsDisplayed()
        confirm.assertIsEnabled()
        rule.onNodeWithText("当前这一层不能选").assertDoesNotExist()
        confirm.performClick()

        val deadline = SystemClock.uptimeMillis() + 15_000
        while (graph.repo.entryAt("mv.txt") == null && SystemClock.uptimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        assertNotNull("移动后应该能在库根查到：${namesAt("")}", graph.repo.entryAt("mv.txt"))
        assertEquals(null, graph.repo.entryAt("uiSeed/nest/mv.txt"))
    }

    /** 验收 10：多选状态下按返回键只退出多选，不能把应用退掉。 */
    @Test
    fun backExitsSelectionNotApp() {
        seedUiTree()
        rule.onNodeWithText("trashme.txt").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("已选 1 项").assertIsDisplayed()

        // 返回键的等价路径：ComponentActivity 的 onBackPressedDispatcher 就是按键真正走的地方
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()

        rule.onNodeWithText("已选 1 项").assertDoesNotExist()
        rule.onNodeWithText("文件库").assertIsDisplayed()
        rule.onNodeWithText("回收站").assertIsDisplayed()
    }

    /** 验收 11：彻底删除与清空回收站都要先确认。 */
    @Test
    fun trashIrreversibleActionsAskFirst() = runBlocking {
        seedUiTree()
        val id = graph.repo.entryAt("trashme.txt")!!.id
        graph.repo.trash(listOf(id))
        rule.waitForIdle()

        rule.onNodeWithText("回收站").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("trashme.txt").performTouchInput { longClick() }
        rule.waitForIdle()

        tapAction("彻底删除")
        rule.onNodeWithText("彻底删除 1 项？").assertIsDisplayed()
        rule.onNodeWithText("彻底删除不进回收站，也没有任何还原途径。").assertIsDisplayed()

        rule.onNodeWithText("取消").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("trashme.txt").assertIsDisplayed()
        assertEquals("取消之后条目必须还在回收站", EntryState.TRASHED, graph.repo.entryById(id)?.state)

        tapAction("彻底删除")
        // 这回点对话框里的确认按钮（标题带"？"，所以精确匹配到按钮）
        rule.onNodeWithText("彻底删除").performClick()
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (graph.repo.entryById(id) != null && SystemClock.uptimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        assertEquals("确认之后条目才真的消失", null, graph.repo.entryById(id))
    }

    /** 验收 14：设置页点重建索引必须看得到结果提示。 */
    @Test
    fun settingsShowsRescanFeedback() {
        seedUiTree()
        rule.onNodeWithText("设置").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("重建索引（磁盘与数据库对账）").performClick()

        // 旧 bug：reload() 整体重建 SettingsState，把刚写进去的 message 抹掉，用户永远看不到回执
        val deadline = SystemClock.uptimeMillis() + 20_000
        var appeared = false
        while (!appeared && SystemClock.uptimeMillis() < deadline) {
            rule.waitForIdle()
            appeared = nodeExists("索引与磁盘一致") ||
                rule.onAllNodes(hasText("补录 ", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("清理 ", substring = true)).fetchSemanticsNodes().isNotEmpty()
            if (!appeared) Thread.sleep(100)
        }
        assertTrue("点重建索引之后没有任何结果提示", appeared)
    }

    /** 验收 9：忙碌遮罩必须吃掉事件；对照组（没有遮罩）要能点动，否则这测试是空的。 */
    @Test
    fun busyOverlayConsumesTouches() {
        var clicks = 0
        rule.setContent {
            Box {
                Button(onClick = { clicks++ }, modifier = Modifier.matchParentSize()) { Text("底下那个按钮") }
                BusyOverlay(true)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("处理中…").assertIsDisplayed()
        rule.onNodeWithText("底下那个按钮").performClick()
        rule.waitForIdle()
        assertEquals("遮罩挂着的时候，底下的按钮照样被点到了", 0, clicks)

        var control = 0
        rule.setContent {
            Box {
                Button(onClick = { control++ }, modifier = Modifier.matchParentSize()) { Text("对照组按钮") }
                BusyOverlay(false)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("对照组按钮").performClick()
        rule.waitForIdle()
        assertEquals("对照失败：没有遮罩时按钮也点不动，那这个测量没意义", 1, control)
    }
}
