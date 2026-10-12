package com.qoder.pocketvault.core

/**
 * 看片 / 听音频的几条判定，刻意写成不依赖 Android 的纯函数：
 * 双击分区、快进快退的夹紧、"下次要不要续播"这三条都是用户能直接感知的行为，
 * 必须能在 JVM 探针里逐条断言（见 scratch/pstub/PlayProbe.kt），
 * 而不是等到装到手机上才发现"退 10 秒退到负数""看完了还从最后三秒续"。
 */
object PlaybackRules {

    enum class TapZone { LEFT, CENTER, RIGHT }

    /** 双击落在屏幕左 / 中 / 右哪一块：左边退、中间播放暂停、右边进。三等分。 */
    fun tapZone(tapX: Float, widthPx: Float): TapZone {
        if (widthPx <= 0f) return TapZone.CENTER
        val third = widthPx / 3f
        return when {
            tapX < third -> TapZone.LEFT
            tapX > 2 * third -> TapZone.RIGHT
            else -> TapZone.CENTER
        }
    }

    /**
     * 快进 / 快退后的目标毫秒数：夹在 [0, duration]。
     * 时长未知（还没 prepare）时一律回 0，不能拿 -1 之类的哨兵当真实位置，
     * 那会让进度条和"已播时间"显示成负数或跳到结尾。
     */
    fun seekTarget(currentMs: Long, durationMs: Long, deltaMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (currentMs + deltaMs).coerceIn(0L, durationMs)
    }

    /** 不足 3 秒的位置不值得跳：点开只会看见一段黑屏，反倒像没续上。 */
    const val MIN_RESUME_MS = 3_000L

    /** 看到 95% 以后就当看完了：下次从头播，比续在最后几秒更合理。 */
    const val FINISHED_PERCENT = 95

    /** 这次打开要不要续到上次那一毫秒。 */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (positionMs < MIN_RESUME_MS) return false
        return positionMs < durationMs * FINISHED_PERCENT / 100
    }

    /**
     * 倍速挡位。上限 2.0：系统解码器再快就开始丢帧、声音也糊了，
     * 列出来的这几档必须包含 1.0（"常速"）。
     */
    val RATES = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

    fun clampRate(rate: Float): Float = rate.coerceIn(RATES.first(), RATES.last())
}
