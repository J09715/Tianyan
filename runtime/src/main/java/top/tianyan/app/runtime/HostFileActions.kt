package top.tianyan.app.runtime

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * 把工作区/附件里的产物交给系统应用：打开、分享、另存都由 SAF 或 ACTION_VIEW/SEND 完成。
 *
 * 一律通过 FileProvider 授权 URI（`${applicationId}.fileprovider`），既不暴露真实路径，
 * 也不需要为每个文件复制一份到 cache；授权只对该次 Intent 有效。
 */
object HostFileActions {

    /** 扩展名优先；未知扩展名回落到 [fallback]（调用方按产物类型给默认值）。 */
    fun mimeType(file: File, fallback: String = "application/octet-stream"): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.US)) ?: fallback

    fun contentUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    /** 用系统默认应用打开；没有可用应用时返回 false，由调用方提示。 */
    fun open(context: Context, file: File): Boolean {
        val uri = contentUri(context, file) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        return startSafely(context, intent)
    }

    /** 系统分享面板。二进制产物（APK/图片/压缩包）走这条，不再伪装成 text/plain。 */
    fun share(context: Context, file: File, subject: String? = null): Boolean {
        val uri = contentUri(context, file) ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        return startSafely(context, intent)
    }

    private fun startSafely(context: Context, intent: Intent): Boolean = runCatching {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
}