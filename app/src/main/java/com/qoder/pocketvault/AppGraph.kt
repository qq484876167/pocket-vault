package com.qoder.pocketvault

import android.app.Application
import android.content.Context
import com.qoder.pocketvault.core.VaultPaths
import com.qoder.pocketvault.core.VaultPrefs
import com.qoder.pocketvault.data.ArchiveEngine
import com.qoder.pocketvault.data.ExportEngine
import com.qoder.pocketvault.data.ImportEngine
import com.qoder.pocketvault.data.IndexVerifier
import com.qoder.pocketvault.data.ThumbnailStore
import com.qoder.pocketvault.data.VaultRepository
import com.qoder.pocketvault.data.db.PocketVaultDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 手工依赖图。所有引擎共享同一个 [VaultRepository]，
 * 保证任何时刻只有一条“磁盘 + 索引”的写入路径。
 */
class AppGraph private constructor(private val appContext: Context) {

    val prefs = VaultPrefs(appContext)
    val paths = VaultPaths(appContext, prefs)
    private val db: PocketVaultDb = PocketVaultDb.build(appContext)
    val repo = VaultRepository(db, paths, prefs)

    /** 导入任务跑在应用级作用域：切到后台或关闭页面都不会半途而废。 */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val importer = ImportEngine(appContext, repo, prefs, engineScope)
    val exporter = ExportEngine(appContext, repo)
    val archives = ArchiveEngine(repo)
    val thumbs = ThumbnailStore(appContext, repo)
    val preview = com.qoder.pocketvault.util.PreviewLoader(repo, thumbs)
    private val verifier = IndexVerifier(db, paths)

    /** 用于页面级的一次性 IO（导出、解压），随 Application 存活。 */
    val ioScope = engineScope

    fun rootPath(): String = paths.rootPath()

    init {
        paths.ensureRoot()
    }

    suspend fun bootstrap(): String {
        repo.purgeExpiredTrash()
        val report = verifier.verify()
        thumbs.trimCache()
        return report.summary
    }

    /** 设置页里的“重建索引”。 */
    suspend fun rescan(): com.qoder.pocketvault.data.VerifyReport {
        repo.purgeExpiredTrash()
        return verifier.verify()
    }

    companion object {
        fun create(context: Context): AppGraph = AppGraph(context.applicationContext)
    }
}

class PocketVaultApp : Application() {

    lateinit var graph: AppGraph
        private set

    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
        bootstrapScope.launch {
            runCatching { graph.bootstrap() }
        }
    }

    override fun onTerminate() {
        bootstrapScope.cancel()
        super.onTerminate()
    }
}

fun Context.appGraph(): AppGraph = (applicationContext as PocketVaultApp).graph
