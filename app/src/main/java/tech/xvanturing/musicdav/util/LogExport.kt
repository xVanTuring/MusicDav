package tech.xvanturing.musicdav.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把落盘日志导出成一个可分享的文本文件，走系统分享面板（微信/邮件/网盘随便发）。
 * 文件写在 cacheDir/exports 下并通过 FileProvider 授权，不需要任何存储权限。
 */
object LogExport {
    private const val EXPORT_DIR = "exports"

    /** 生成导出文件；日志为空时返回 null。 */
    fun writeExportFile(context: Context): File? {
        val text = AppLog.dump(context)
        if (text.isBlank()) return null

        val dir = File(context.cacheDir, EXPORT_DIR).apply { if (!exists()) mkdirs() }
        // 只保留本次导出，避免 cacheDir 里堆一堆旧文件
        dir.listFiles()?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "musicdav_log_$stamp.txt")
        file.writeText(header(context) + text)
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, file.name)
    }

    /** 导出文件开头附一段设备/版本信息，光看日志正文常常判断不出环境。 */
    private fun header(context: Context): String {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCodeCompat()})"
        } catch (_: Throwable) {
            "unknown"
        }
        return buildString {
            append("==== MusicDav 日志导出 ====\n")
            append("时间: ").append(Date()).append('\n')
            append("应用: ").append(version).append('\n')
            append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("==========================\n\n")
        }
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
}
