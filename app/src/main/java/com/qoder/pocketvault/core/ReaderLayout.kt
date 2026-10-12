package com.qoder.pocketvault.core

/**
 * 小说排版的几条换算，写成不依赖 Android 的纯函数，能在 JVM 探针里逐条断言
 * （scratch/pstub/ReaderProbe.kt）。
 *
 * 这些都是"看得见但容易算错"的东西：首行缩进到底缩几个字、限宽后小屏会不会被压成
 * 一行三个字、翻页动画换算的速度档位是不是单调递增、剩余时间估算在刚翻开第一页时
 * 是不是该闭嘴别说"还剩 900 小时"。
 */
object ReaderLayout {

    /** 全角空格：中文书的一个字符宽度就是它，缩进用它才不会因字体不同而歪。 */
    const val IDEOGRAPHIC_SPACE = '　'

    /** 首行缩进前缀。0 / 1 / 2 个字；负数当 0 处理，不能让排版反过来把文字挤出屏幕。 */
    fun indentPrefix(chars: Int): String = if (chars <= 0) "" else IDEOGRAPHIC_SPACE.toString().repeat(chars.coerceAtMost(4))

    /**
     * 段落之间的空隙（单位 dp，按字号折算）。
     * 0.40 是这轮改动前就有的固定值，"段间距"滑块是在它之上加 0–1.5 倍字号。
     */
    fun paragraphGapDp(fontSizeSp: Float, extraFactor: Float): Float =
        (fontSizeSp * (0.40f + extraFactor.coerceIn(0f, 1.5f))).coerceAtLeast(0f)

    /**
     * 正文可用的最大宽度（像素）。
     * [maxCharsPerLine] <= 0 表示不限宽（用满页宽）；限宽时按"字号 = 一个全角字宽"估，
     * 并且永远不小于两个字，否则在小屏 + 大字号下会变成一行只放得下一个字、无法阅读。
     */
    fun maxContentWidthPx(
        viewportWidthPx: Float,
        maxCharsPerLine: Int,
        fontSizePx: Float,
    ): Float {
        if (maxCharsPerLine <= 0) return viewportWidthPx
        val perChar = fontSizePx.coerceAtLeast(1f)
        val wanted = perChar * maxCharsPerLine.coerceIn(2, 120)
        return wanted.coerceAtMost(viewportWidthPx.coerceAtLeast(perChar * 2))
    }

    /**
     * 自动滚屏每帧要滚动的像素。档位 0 = 关闭，1–5 递增。
     * 档位越高压得越低：手机上 1 像素/帧已经是慢得难受的巡航速度。
     */
    fun autoScrollPxPerFrame(level: Int, viewportHeightPx: Float): Float {
        if (level <= 0) return 0f
        val ratio = when (level) {
            1 -> 0.002f
            2 -> 0.004f
            3 -> 0.008f
            4 -> 0.014f
            else -> 0.024f
        }
        return viewportHeightPx.coerceAtLeast(1f) * ratio
    }

    /** 进度显示的三种口径。 */
    const val PROGRESS_OFF = 0
    const val PROGRESS_PERCENT = 1
    const val PROGRESS_REMAINING = 2

    /**
     * 按"今天已经读的时长 + 读到的百分比"估还剩多少分钟。
     *
     * 百分比低于 [MIN_PERCENT_FOR_ETA] 时返回 null：刚翻开两页就报"还剩 40 小时"，
     * 那种数字只会让人觉得在瞎猜。
     */
    const val MIN_PERCENT_FOR_ETA = 5

    fun remainingMinutes(secondsToday: Int, percent: Int): Int? {
        if (percent < MIN_PERCENT_FOR_ETA || percent > 100) return null
        val perPercent = secondsToday.toFloat() / percent
        return ((perPercent * (100 - percent)) / 60f).toInt().coerceAtLeast(0)
    }

    /** 底栏那一行字。 */
    fun progressLabel(mode: Int, percent: Int, remainingMinutes: Int?): String = when (mode) {
        PROGRESS_OFF -> ""
        PROGRESS_REMAINING -> remainingMinutes?.let { "还剩约 $it 分钟" } ?: "$percent%"
        else -> "$percent%"
    }
}
