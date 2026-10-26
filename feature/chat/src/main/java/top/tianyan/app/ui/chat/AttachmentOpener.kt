package top.tianyan.app.ui.chat

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.MessageAttachment

/** 定位沙箱附件的宿主副本（attachmentsDir 的镜像）。 */
internal fun attachmentFile(context: Context, attachment: MessageAttachment): java.io.File? {
    val name = attachment.guestPath.substringAfterLast('/').ifBlank { attachment.name }
    if (name.isBlank()) return null
    val candidate = java.io.File(context.filesDir, "linux-runtime/attachments/$name")
    return candidate.takeIf { it.exists() && it.length() > 0L }
}

/**
 * 点击文件附件：用系统查看器打开。URI 走 FileProvider 并附带只读授权，
 * mimeType 优先用发送时保留的类型，缺失时按扩展名回退。
 */
internal fun openAttachment(context: Context, attachment: MessageAttachment) {
    val mimeType = attachment.mimeType
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            attachment.name.substringAfterLast('.', "").lowercase(),
        )
        ?: "*/*"
    val file = attachmentFile(context, attachment)
    val uri = file?.let { f ->
        runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }.getOrNull()
    }
    if (uri == null) {
        Toast.makeText(context, context.getString(R.string.chat_attachment_missing, attachment.name), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(android.content.Intent.createChooser(intent, attachment.name))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.chat_attachment_missing, attachment.name), Toast.LENGTH_SHORT).show()
    }
}
