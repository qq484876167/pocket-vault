package com.qoder.pocketvault.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.qoder.pocketvault.data.db.VaultEntry
import java.io.File

/**
 * 跨应用访问的唯一通道：FileProvider + 临时读授权。
 * 私有目录本身对其他应用不可见，因此“用其他应用打开”必须先经过这里，
 * 授权随本次任务结束失效，不会把文件永久暴露出去。
 */
object FileBridge {

    fun authority(context: Context): String = context.packageName + ".fileprovider"

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /** 交给外部应用打开（播放器、Office、PDF 阅读器等）。 */
    fun openWith(context: Context, file: File, entry: VaultEntry): String? {
        val uri = runCatching { contentUri(context, file) }
            .getOrElse { return "无法生成分享授权：${it.message ?: it.javaClass.simpleName}" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mimeType ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "打开 ${entry.name}"))
            null
        } catch (e: ActivityNotFoundException) {
            "这台手机上没有能打开 ${entry.name} 的应用"
        } catch (e: SecurityException) {
            "系统拒绝了本次授权"
        }
    }

    fun share(context: Context, file: File, entry: VaultEntry): String? {
        val uri = runCatching { contentUri(context, file) }
            .getOrElse { return "无法生成分享授权" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = entry.mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "分享 ${entry.name}"))
            null
        } catch (e: ActivityNotFoundException) {
            "没有可用的分享目标"
        }
    }

    /** 跳转到系统“应用详情/存储设置”，用于提示用户清理缓存。 */
    fun openAppSettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
