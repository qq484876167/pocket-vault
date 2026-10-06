package com.qoder.pocketvault.core

/**
 * 路径规则里"纯字符串"的那一半，刻意不依赖 Context：
 * 这样条目名清洗与相对路径规范化可以在 JVM 上直接跑测试（真归档、恶意包都在测）。
 *
 * VaultPaths 的伴生对象只是转发到这里，避免同一规则出现两份实现。
 */
object PathRules {

    const val TRASH_DIR = ".trash"
    const val MAX_NAME_LENGTH = 180
    private val RESERVED = setOf(TRASH_DIR, ".nomedia")

    /**
     * 校验并规范化相对路径：拒绝 ..、保留名与控制字符。
     * 返回 '' 表示根目录。
     */
    fun normalizeRelative(relativePath: String): String {
        if (relativePath.isBlank()) return ""
        val segments = relativePath.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        val out = StringBuilder()
        for (segment in segments) {
            require(segment != "..") { "越界路径段: $relativePath" }
            require(segment !in RESERVED) { "保留目录/文件不可访问: $segment" }
            require(segment.none { it < ' ' }) { "路径包含控制字符" }
            if (out.isNotEmpty()) out.append('/')
            out.append(segment)
        }
        return out.toString()
    }

    /** 落盘安全名：替换分隔符、去掉首尾点与空格，保留长度信息用于查重。 */
    fun sanitizeName(rawName: String): String {
        val replaced = rawName.map { c ->
            when {
                c == '/' || c == '\\' -> '_'
                c == '\u0000' -> '_'
                c < ' ' -> '_'
                else -> c
            }
        }.joinToString("")
        var name = replaced.trim().trimEnd('.').trim()
        if (name.isEmpty() || name == "." || name == "..") name = "未命名文件"
        if (name.startsWith(".")) name = "_" + name // 避免创建隐藏文件或撞上保留名
        if (name in RESERVED) name = "_$name"
        if (name.length > MAX_NAME_LENGTH) {
            val ext = name.substringAfterLast('.', "")
            val stem = name.substringBeforeLast('.', name)
            name = if (ext.isNotEmpty() && ext.length < 12) {
                stem.take(MAX_NAME_LENGTH - ext.length - 1) + "." + ext
            } else {
                stem.take(MAX_NAME_LENGTH)
            }
        }
        return name
    }

    fun isReserved(segment: String): Boolean = segment in RESERVED
}
