package com.qoder.pocketvault.ui.vm

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 连 [CancellationException] 一起吞成"失败"，后果不止是提示难看：
 * 忙碌状态被复位、往已经取消的作用域写状态、批处理循环还会继续跑下一项，
 * 最后把"其实被用户取消了"当成完成汇报。取消必须原样传出去。
 *
 * 正确写法是 `runCatching { ... }.onFailure { it.rethrowIfCancelled() }`。
 *
 * **本函数只在 [CancellationException] 时抛出**，其余情况正常返回，交给调用方后面的
 * fold / onFailure 分支变成界面提示。要是把它写成返回 [Nothing] 并在末尾无条件 `throw this`，
 * 编译器和 lint 都不会报（"所有路径都抛"本身就是 Nothing 的合法用法），但 17 处调用点会
 * 一起变成分支不可达：普通失败也被重新抛出，`_busy` 停在 true（遮罩一直挡着、没有提示），
 * "读取索引失败"那类分支永远进不去。这条语义由 `scratch/pstub/CancelProbe.kt` 断言。
 */
internal fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}
