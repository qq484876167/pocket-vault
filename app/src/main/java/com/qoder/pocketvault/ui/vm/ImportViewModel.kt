package com.qoder.pocketvault.ui.vm

import androidx.lifecycle.ViewModel
import com.qoder.pocketvault.AppGraph
import com.qoder.pocketvault.data.ImportProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局导入进度：跨标签页可见，因为导入跑在应用级作用域而不是某个页面的 viewModelScope。
 * 发起导入的动作由浏览页（BrowserHost）直接调用 ImportEngine。
 */
class ImportViewModel(private val graph: AppGraph) : ViewModel() {

    val progress: StateFlow<ImportProgress> = graph.importer.progress

    fun cancel() = graph.importer.cancel()

    fun dismissProgress() = graph.importer.dismissProgress()
}
