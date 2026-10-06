package com.qoder.pocketvault.data

import com.qoder.pocketvault.core.formatBytes

/**
 * 解压的字节预算：开工前的总量核对 + 写入过程中的剩余空间看门狗 + 体积上限。
 *
 * 单独成类是因为包内声明的大小并不总是可信（单文件 gz / bz2 / xz 根本没有，
 * tar 里也可能被伪造），只看开工前那一次核对等于对这类包完全不设防。
 * 这里不碰 Android 也不碰 Room，可以在纯 JVM 上直接跑断言。返回非 null 即"该拒绝"的消息。
 */
internal class ExtractBudget(
    private val safetyMarginBytes: Long,
    private val maxEntryBytes: Long,
    private val maxTotalBytes: Long,
    /** 每写这么多字节复查一次剩余空间。 */
    private val recheckEveryBytes: Long,
) {

    // 第一次写入后就复查：剩余空间本来就只剩几 MB 时，等写满 32 MB 再查已经太晚了
    private var nextRecheckAt = 0L
    private var total = 0L

    /** 开工前的核对；声明大小拿不到（<0）时放行，交给写入过程中的看门狗。 */
    fun refuseBeforeStart(requiredBytes: Long, freeBytes: Long): String? {
        if (requiredBytes < 0L || freeBytes <= 0L) return null
        if (requiredBytes > maxTotalBytes) {
            return "这个包声明解压后有 ${formatBytes(requiredBytes)}，超过单次解压上限 " +
                formatBytes(maxTotalBytes) + "，已拒绝"
        }
        if (requiredBytes > freeBytes - safetyMarginBytes) {
            return "空间不够：需要约 ${formatBytes(requiredBytes)}，可用 ${formatBytes(freeBytes)}"
        }
        return null
    }

    /** 单个条目写入前的核对（靠包内声明的大小）。 */
    fun refuseEntryBeforeWrite(declaredBytes: Long): String? {
        if (declaredBytes > maxEntryBytes) {
            return "这个条目声明解压后有 ${formatBytes(declaredBytes)}，超过单文件上限 " +
                formatBytes(maxEntryBytes) + "，已跳过"
        }
        return null
    }

    /**
     * 写入过程中按块调用：累计写满 [recheckEveryBytes] 才复查一次剩余空间，
     * 低于安全余量即中止。伪造大小或压根没大小的包都拦在这里。
     * [freeBytes] 是懒取的（StatFs 有系统调用开销），只在到点复查时才会被调用。
     */
    fun watch(write: Long, freeBytes: () -> Long): String? {
        total += write
        if (total > maxTotalBytes) {
            return "累计解压已超过 ${formatBytes(maxTotalBytes)}，已中断"
        }
        if (total < nextRecheckAt) return null
        nextRecheckAt = total + recheckEveryBytes
        val free = freeBytes()
        if (free <= 0L) return null
        if (free < safetyMarginBytes) {
            return "剩余空间不足（只剩 ${formatBytes(free)}），已中断解压"
        }
        return null
    }

    companion object {
        /** 留一点余量：解压的临时文件与库本体共用一个分区。 */
        const val SAFETY_MARGIN = 32L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024
        const val RECHECK_EVERY_BYTES = 8L * 1024 * 1024

        fun forVault() = ExtractBudget(SAFETY_MARGIN, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES, RECHECK_EVERY_BYTES)
    }
}
