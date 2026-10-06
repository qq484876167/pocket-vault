package com.qoder.pocketvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qoder.pocketvault.data.db.ROOT_ID
import com.qoder.pocketvault.ui.screen.DetailScreen
import com.qoder.pocketvault.ui.screen.FolderScreen
import com.qoder.pocketvault.ui.screen.ImportProgressHost
import com.qoder.pocketvault.ui.screen.LibraryScreen
import com.qoder.pocketvault.ui.screen.SearchScreen
import com.qoder.pocketvault.ui.screen.SettingsScreen
import com.qoder.pocketvault.ui.screen.TrashScreen
import com.qoder.pocketvault.ui.viewer.MediaViewerScreen
import com.qoder.pocketvault.ui.viewer.ViewerViewModel
import com.qoder.pocketvault.ui.vm.DetailViewModel
import com.qoder.pocketvault.ui.vm.FolderViewModel
import com.qoder.pocketvault.ui.vm.ImportViewModel
import com.qoder.pocketvault.ui.vm.LibraryViewModel
import com.qoder.pocketvault.ui.vm.SearchViewModel
import com.qoder.pocketvault.ui.vm.SettingsViewModel
import com.qoder.pocketvault.ui.vm.TrashViewModel
import com.qoder.pocketvault.ui.vm.graphViewModel

private data class TopDestination(val route: String, val label: String, val icon: ImageVector)

private val TOP_LEVEL = listOf(
    TopDestination("library", "文件库", Icons.Filled.Layers),
    TopDestination("folder", "目录", Icons.Filled.Folder),
    TopDestination("search", "搜索", Icons.Filled.Search),
    TopDestination("trash", "回收站", Icons.Filled.Delete),
    TopDestination("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun VaultApp() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val navigator = remember(navController) { Navigator(navController) }
    val importVm = graphViewModel { ImportViewModel(it) }
    val progress by importVm.progress.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()
    // 查看器要沉浸式全屏：隐藏底部导航，让图片/视频/PDF 占满整屏
    val immersive = currentEntry?.destination?.route?.startsWith("viewer") == true

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { if (!immersive) VaultBottomBar(navController) },
    ) { innerPadding ->
        CompositionLocalProvider(
            LocalSnackbar provides snackbarHostState,
            LocalNavigator provides navigator,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                NavHost(navController = navController, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(graphViewModel { LibraryViewModel(it) })
                    }
                    composable("folder") {
                        FolderScreen(graphViewModel { FolderViewModel(it, ROOT_ID) })
                    }
                    composable(
                        route = "folder/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: ROOT_ID
                        FolderScreen(graphViewModel { FolderViewModel(it, id) })
                    }
                    composable(
                        // scope 可空：底栏「搜索」走无参的 "search"，目录页走 "search?scope= id"
                        route = "search?scope={scope}",
                        arguments = listOf(navArgument("scope") { type = NavType.LongType; defaultValue = ROOT_ID }),
                    ) { entry ->
                        val scope = entry.arguments?.getLong("scope") ?: ROOT_ID
                        SearchScreen(graphViewModel { SearchViewModel(it, scope) })
                    }
                    composable("trash") {
                        TrashScreen(graphViewModel { TrashViewModel(it) })
                    }
                    composable("settings") {
                        SettingsScreen(graphViewModel { SettingsViewModel(it) })
                    }
                    composable(
                        route = "detail/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        DetailScreen(graphViewModel { DetailViewModel(it, id) })
                    }
                    composable(
                        route = "viewer/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        MediaViewerScreen(graphViewModel { ViewerViewModel(it, id) })
                    }
                }

                if (progress.active || progress.message != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 4.dp),
                    ) {
                        ImportProgressHost(
                            progress = progress,
                            onCancel = importVm::cancel,
                            onDismiss = importVm::dismissProgress,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationBar {
        TOP_LEVEL.forEach { item ->
            // 路由模板可能带可选查询参数（search?scope={scope}），比高亮时先剥掉参数部分
            val selected = currentDestination?.hierarchy?.any { it.route?.substringBefore("?") == item.route } == true ||
                (item.route == "folder" && currentDestination?.route?.startsWith("folder/") == true)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}
