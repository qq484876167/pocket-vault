package com.qoder.pocketvault.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qoder.pocketvault.AppGraph
import com.qoder.pocketvault.PocketVaultApp

/** 把 AppGraph 注入 ViewModel，避免在每个页面手写 Factory。 */
@Composable
inline fun <reified T : ViewModel> graphViewModel(noinline create: (AppGraph) -> T): T {
    val app = LocalContext.current.applicationContext as PocketVaultApp
    val factory = remember(app) {
        viewModelFactory {
            initializer { create(app.graph) }
        }
    }
    return viewModel(factory = factory)
}
