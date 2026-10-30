package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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

/**
 * 全屏图片预览：点缩略图后弹出，点任意处关闭。支持 data URL、file:// 与沙箱 /attachments 路径。
 *
 * 为什么需要它：缩略图只有 130dp，用户点开想看清细节时没有更大的视图可用 ——
 * 之前点击只是弹出消息操作菜单，图片本身看不全。
 */
@androidx.compose.runtime.Composable
internal fun ChatImagePreviewDialog(imageUrl: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val data = androidx.compose.runtime.remember(imageUrl) {
        when {
            imageUrl.startsWith("file://") -> java.io.File(imageUrl.removePrefix("file://"))
            imageUrl.startsWith("/attachments/") ->
                java.io.File(context.filesDir, "linux-runtime/attachments/${imageUrl.substringAfterLast('/')}")
            imageUrl.startsWith("/") -> java.io.File(imageUrl)
            else -> imageUrl
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.94f))
                .clickable(onClick = onDismiss),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            coil3.compose.AsyncImage(
                model = coil3.request.ImageRequest.Builder(context).data(data).build(),
                contentDescription = "图片预览",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 用系统播放器打开视频/音频附件。
 *
 * 为什么单独一个函数：视频不适合内嵌预览（需要解码器与控制器），
 * 交给系统播放器是 Android 上最稳的做法，且能自动适配各种编码。
 * 关键点是从沙箱副本取真实文件并用 FileProvider 授权 —— 直接传 content:// 或
 * 沙箱路径（/attachments/...）外部播放器都打不开。
 */
internal fun openMediaAttachment(context: Context, attachment: MessageAttachment): Boolean {
    val file = attachmentFile(context, attachment) ?: return false
    val mime = attachment.mimeType
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(attachment.name.substringAfterLast('.', "").lowercase())
        ?: return false
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(intent, attachment.name))
        true
    }.getOrDefault(false)
}

/** 是否为视频/音频附件（交给系统播放器而不是文件查看器）。 */
internal fun MessageAttachment.isPlayableMedia(): Boolean {
    val mime = mimeType.orEmpty().lowercase()
    if (mime.startsWith("video/") || mime.startsWith("audio/")) return true
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in MEDIA_EXTENSIONS
}

private val MEDIA_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts",
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
)
