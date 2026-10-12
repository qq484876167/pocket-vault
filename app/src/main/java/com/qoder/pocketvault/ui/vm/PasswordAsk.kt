package com.qoder.pocketvault.ui.vm

/**
 * 批量解压正在向用户要密码的那一个包。
 *
 * 单独成文件（只依赖标准库）是为了让 [statusLine] 这张话术表能在纯 JVM 探针里逐条断言：
 * "还没给密码 / 密码不对 / 试了很多次"是三种不同状态，各说各的话，
 * 绝不能统一压成"解压失败"——那会让用户以为包坏了，或者以为应用没响应。
 */
data class PasswordAsk(val fileName: String, val attempts: Int, val wrong: Boolean) {

    /** 输入框上方那一句提示。 */
    val statusLine: String
        get() = when {
            wrong && attempts > 1 ->
                "密码不对（已经试了 $attempts 次），也可能是这个包用了不支持的加密方式。"
            wrong -> "密码不对，请再输入一次。"
            else -> "输入密码后点「解压」。留空点「跳过这一个」就是不解这个包。"
        }

    /** 上一次给的密码打不开时才用报错色。 */
    val isError: Boolean get() = wrong
}
