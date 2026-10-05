package com.qoder.pocketvault.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.qoder.pocketvault.appGraph
import com.qoder.pocketvault.ui.screen.OnboardingScreen
import com.qoder.pocketvault.ui.theme.PocketVaultTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PocketVaultTheme {
                VaultEntryPoint()
            }
        }
    }
}

/** 首次启动先走导入引导，之后进入常规文件管理界面。 */
@Composable
private fun VaultEntryPoint() {
    val graph = LocalContext.current.appGraph()
    var onboarded by remember { mutableStateOf(graph.prefs.onboarded) }
    if (onboarded) {
        VaultApp()
    } else {
        OnboardingScreen(onFinished = {
            graph.prefs.onboarded = true
            onboarded = true
        })
    }
}
