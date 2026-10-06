package com.qoder.pocketvault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 接线自检：确认仪器化环境、AppGraph 与私有目录在模拟器上真的可用。 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun graphIsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PocketVaultApp
        assertTrue("私有根目录没建出来：${app.graph.rootPath()}", java.io.File(app.graph.rootPath()).isDirectory)
    }
}
