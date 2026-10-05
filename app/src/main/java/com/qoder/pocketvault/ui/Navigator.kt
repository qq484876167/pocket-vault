package com.qoder.pocketvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavHostController

/** 页面跳转集中在这里，屏幕组件本身不直接依赖 NavController。 */
class Navigator(private val controller: NavHostController) {
    /**
     * 目录路由只带数据库行 id，不带相对路径：
     * 文件夹名是用户任意输入（可能含 % / # ? 等），URI 编码再解码容易出错。
     */
    fun openFolder(folderId: Long) = controller.navigate("folder/$folderId")
    fun openRoot() = controller.navigate("folder")
    fun openDetail(id: Long) = controller.navigate("detail/$id")
    fun openViewer(id: Long) = controller.navigate("viewer/$id")
    fun openSearch() = controller.navigate("search")
    fun openTrash() = controller.navigate("trash")
    fun openSettings() = controller.navigate("settings")
    fun goBack() = controller.popBackStack()
}

val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }
val LocalNavigator = staticCompositionLocalOf<Navigator?> { null }
