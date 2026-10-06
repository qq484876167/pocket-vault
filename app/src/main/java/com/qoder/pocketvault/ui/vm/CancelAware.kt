package com.qoder.pocketvault.ui.vm

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 连 [CancellationException] 一起吞成"失败"，后果不止是提示难看：
 * 忙碌状态被复位、往已经取消的作用域写状态、批处理循环还会继续跑下一项，
 * 最后把"其实被用户取消了"当成完成汇报。取消必须原样传出去。
 *
 * 正确写法是 `runCatching { ... }.onFailure { it.rethrowIfCancelled() }`。
 */
internal fun Throwable.rethrowIfCancelled(): Nothing {
    if (this is CancellationException) throw this
    throw this
}
