package com.qoder.pocketvault.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024
        index++
    }
    return if (value >= 100) "%.0f %s".format(Locale.CHINA, value, units[index])
    else "%.1f %s".format(Locale.CHINA, value, units[index])
}

fun formatDuration(millis: Long?): String {
    if (millis == null || millis <= 0) return "—"
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val dayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val monthFormat = SimpleDateFormat("yyyy年M月", Locale.CHINA)

fun formatDateTime(millis: Long): String = dayFormat.format(Date(millis))

/** 列表里用“今天 / 昨天 / 3 天前 / 具体日期”，与系统文件管理器一致。 */
fun formatRelativeDay(millis: Long, now: Long = System.currentTimeMillis()): String {
    val dayMillis = 24L * 3600 * 1000
    val days = abs((now - millis)) / dayMillis
    return when {
        millis >= now - dayMillis -> "今天"
        days <= 1 -> "昨天"
        days < 7 -> "${days} 天前"
        days < 30 -> "${days / 7} 周前"
        now - millis < 365 * dayMillis -> monthFormat.format(Date(millis))
        else -> dayFormat.format(Date(millis))
    }
}

fun folderDisplayName(relativePath: String): String =
    relativePath.trim('/').substringAfterLast('/').ifEmpty { "文件库根目录" }

/** 面包屑：根目录 + 每一级路径，点击即可跳转。 */
fun breadcrumbOf(relativePath: String): List<Pair<String, String>> {
    val clean = relativePath.trim('/')
    if (clean.isEmpty()) return listOf("" to "文件库")
    val parts = clean.split('/')
    val acc = StringBuilder()
    val out = ArrayList<Pair<String, String>>(parts.size + 1)
    out += "" to "文件库"
    for ((i, part) in parts.withIndex()) {
        if (acc.isNotEmpty()) acc.append('/')
        acc.append(part)
        out += acc.toString() to (if (i == parts.lastIndex) part else part)
    }
    return out
}

/** 在 dirRel 下为 name 找一个不冲突的逻辑路径，形如 报告(1).docx。 */
fun dedupeRelative(dirRel: String, name: String, existing: Set<String>): String {
    val stem = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    var candidate = joinRelative(dirRel, name)
    if (candidate !in existing) return candidate
    var i = 1
    while (i < 10_000) {
        candidate = joinRelative(dirRel, "$stem($i)$ext")
        if (candidate !in existing) return candidate
        i++
    }
    return joinRelative(dirRel, "$stem-${System.currentTimeMillis()}$ext")
}

fun joinRelative(dirRel: String, name: String): String {
    val dir = dirRel.trim('/')
    return if (dir.isEmpty()) name else "$dir/$name"
}

fun parentRelative(relativePath: String): String = relativePath.trim('/').substringBeforeLast('/', "")
