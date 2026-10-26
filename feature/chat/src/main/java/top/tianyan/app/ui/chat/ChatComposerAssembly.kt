package top.tianyan.app.ui.chat

import android.content.Context
import top.tianyan.app.feature.chat.R

/**
 * 组装输入框提交正文：用户文本 + 附件挂载说明（来自 [ChatViewModel.sendFromComposer]，
 * 抽出以控制主 ViewModel 文件尺寸）。
 */
internal fun buildComposerMessage(
    context: Context,
    trimmedInput: String,
    attachments: List<ChatAttachment>,
): String {
    val imageUrls = attachments.mapNotNull { it.base64DataUrl }
    val nonImageFiles = attachments.filter { !it.isImage }
    return buildString {
        if (trimmedInput.isNotBlank()) {
            append(trimmedInput)
        } else if (nonImageFiles.isNotEmpty()) {
            append(context.getString(R.string.chat_attachment_files_prompt))
        } else if (imageUrls.isNotEmpty()) {
            append(context.getString(R.string.chat_attachment_images_prompt))
        }
        if (attachments.isNotEmpty()) {
            append(context.getString(R.string.chat_attachment_mount_header))
            attachments.forEachIndexed { i, att ->
                val guestPath = att.guestFilePath ?: "/attachments/${att.name}"
                val kind = context.getString(if (att.isImage) R.string.chat_attachment_image else R.string.chat_attachment_file)
                append(context.getString(R.string.chat_attachment_line, i + 1, kind, att.name, AttachmentHelper.formatFileSize(att.sizeBytes), guestPath))
            }
            append(context.getString(R.string.chat_attachment_access_hint))
        }
    }
}
