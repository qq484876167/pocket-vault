package com.qoder.pocketvault.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 常驻的 PDF 文档句柄 + 页位图缓存。
 * PdfRenderer 不是线程安全的，因此所有渲染串行化在一把锁后面。
 *
 * 渲染一律按目标宽度整页画一遍（和系统阅读器一样），缓存按「页码@宽度」存，
 * 淘汰时不主动 recycle —— 位图可能还在被上一帧绘制，回收会直接崩溃。
 */
class PdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {

    val pageCount: Int = renderer.pageCount

    private val renderLock = Mutex()

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 渲染第 [index] 页；[targetWidthPx] 一般就是屏幕像素宽度的 1.5 倍。
     * 异常一律抛给调用方——把真实原因显示在界面上比在这里吞掉有用得多。
     */
    suspend fun page(index: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index < 0 || index >= pageCount) return@withContext null
        val width = targetWidthPx.coerceIn(360, RENDER_WIDTH)
        val key = "$index@$width"
        cache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }
        renderLock.withLock {
            cache.get(key)?.takeIf { !it.isRecycled }?.let { return@withLock it }
            val rendered = renderAt(index, width)
            cache.put(key, rendered)
            rendered
        }
    }

    private fun renderAt(index: Int, width: Int): Bitmap =
        renderer.openPage(index).use { page ->
            val aspect = page.height.toFloat() / page.width.coerceAtLeast(1)
            val height = (width * aspect).toInt().coerceAtLeast(1)
            // PdfRenderer 硬性要求目标位图是 ARGB_8888，其它 config（RGB_565 / RGBA_F16 /
            // HARDWARE）一律直接抛 IllegalArgumentException("Unsupported pixel format")。
            // 内存只能靠压尺寸或分层重渲染来省，换像素格式这条路是死的。
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }

    fun clearCache() {
        cache.evictAll()
    }

    /**
     * 关闭也要过同一把锁：退出时可能正好有一页在 renderAt（阻塞、不可取消），
     * 和 renderer.close() 并发会抛异常甚至 native 崩溃。
     */
    suspend fun closeAsync() = renderLock.withLock {
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /**
         * 整页渲染宽度：1.5 倍屏宽、封顶 1600px。
         * PdfViewer 也引用这一个值，避免两处各自改一半。
         */
        const val RENDER_WIDTH = 1600

        /**
         * ARGB_8888 下一张 1600×2262 的整页位图约 13.8 MB，
         * 原来固定 32 MB 只装得下 2 页 —— 而 PdfViewer 一次预取 3 页，
         * 相邻页会在真正翻到之前就被挤出去，预取形同虚设。
         * 改为跟堆上限挂钩：低端机 24 MB 起步，高端机封顶 96 MB。
         */
        private val CACHE_BYTES: Int =
            (Runtime.getRuntime().maxMemory() / 8).coerceIn(24L shl 20, 96L shl 20).toInt()

        /** 打开也在 IO 上：ParcelFileDescriptor + PdfRenderer 都要读盘。 */
        suspend fun open(file: File): PdfDocument? = withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (e: Exception) {
                    descriptor.close()
                    throw e
                }
                PdfDocument(descriptor, renderer)
            }.getOrNull()
        }
    }
}
