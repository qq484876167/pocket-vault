package com.qoder.pocketvault

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qoder.pocketvault.util.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验收 4：整页渲染必须发生在 IO 线程，主线程在渲染期间要还能处理消息。
 *
 * 仪器化测试的方法体跑在 instrumentation 线程上，应用的主线程此时正常泵消息，
 * 所以"往主线程 post 一个自计时的 Runnable，看它在渲染期间跑了多少次"就是
 * "主线程有没有被占住"的直接测量；同一个测试里还带一个**对照**：
 * 故意让主线程睡 400 ms，此时计数必须几乎不涨——否则这个测量本身没意义。
 */
@RunWith(AndroidJUnit4::class)
class PdfRenderThreadTest {

    private lateinit var context: Context
    private lateinit var pdf: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        pdf = File(context.filesDir, "pdf-thread-test.pdf")
        context.assets.open("many-pages.pdf").use { input ->
            pdf.writeBytes(input.readBytes())
        }
        assertTrue("600 页样本没拷出来：${pdf.length()} bytes", pdf.length() > 100_000)
    }

    @Test
    fun renderingKeepsMainThreadFree() = runBlocking {
        val doc = PdfDocument.open(pdf)
        assertTrue("PDF 打不开（P0-1 之后 open 也在 IO 上）", doc != null)
        doc!!
        assertEquals(600, doc.pageCount)

        val ticks = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                ticks.incrementAndGet()
                if (!stop.get()) handler.postDelayed(this, 8)
            }
        }
        handler.post(tick)
        // 先让计时器真的跑起来，否则后面的"没涨"可能是没启动
        Thread.sleep(60)

        val started = SystemClock.uptimeMillis()
        val bitmap = doc.page(3, PdfDocument.RENDER_WIDTH)
        val cost = SystemClock.uptimeMillis() - started

        val during = ticks.get()
        stop.set(true)
        handler.removeCallbacks(tick)

        assertTrue("第 4 页没渲染出来", bitmap != null && !bitmap.isRecycled)
        assertEquals("PdfRenderer 只接受 ARGB_8888（1.8.0 换 565 把 PDF 全弄坏过）",
            Bitmap.Config.ARGB_8888, bitmap!!.config)
        assertTrue("页宽应当按目标宽度出图：${bitmap.width}", bitmap.width in 360..PdfDocument.RENDER_WIDTH)

        // 主线程没被占住的话，这段窗口里它至少能跑好几轮
        assertTrue(
            "渲染 ${cost}ms 期间主线程只跑了 $during 次消息，说明渲染还在主线程上",
            during >= 3,
        )

        // 对照：故意把主线程睡住 400 ms，同样的测量必须几乎不涨——证明上面那条不是白给的
        val base = ticks.get()
        val observe = AtomicBoolean(true)
        val control = AtomicInteger(0)
        val controlTick = object : Runnable {
            override fun run() {
                control.incrementAndGet()
                if (observe.get()) handler.postDelayed(this, 8)
            }
        }
        handler.post(controlTick)
        Thread.sleep(40)
        val beforeBlock = control.get()
        handler.post { Thread.sleep(400) }        // 这一条在主线程上睡
        Thread.sleep(150)
        observe.set(false)
        handler.removeCallbacks(controlTick)
        val blockedDelta = control.get() - beforeBlock
        assertTrue(
            "对照失败：主线程被 sleep 占住时计数仍涨了 $blockedDelta（基线 $beforeBlock），" +
                "说明这个测量测不出阻塞",
            blockedDelta <= 2,
        )

        doc.closeAsync()
    }

    /** 并发渲染 + 关闭不能炸（P1-13 的渲染锁）。 */
    @Test
    fun concurrentRenderThenCloseIsSafe() = runBlocking {
        val doc = PdfDocument.open(pdf) ?: return@runBlocking
        val pages = (0..5).map { i -> async(Dispatchers.IO) { doc.page(i * 7 + 1, 900) } }
        val bitmaps = pages.map { it.await() }
        assertEquals(6, bitmaps.count { it != null })
        doc.closeAsync()
    }

    /** 越界页号返回 null 而不是抛（界面按这个判"翻到头了"）。 */
    @Test
    fun outOfRangePagesReturnNull() = runBlocking {
        val doc = PdfDocument.open(pdf) ?: return@runBlocking
        assertEquals(null, doc.page(-1, 900))
        assertEquals(null, doc.page(doc.pageCount, 900))
        doc.closeAsync()
    }
}
