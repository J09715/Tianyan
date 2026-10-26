package top.tianyan.app.ui.chat

import android.content.Context
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.MessageAttachment

/**
 * 输入框附件 → 发送载荷。
 *
 * 为什么单独成文件：ChatViewModel 已到行数棘轮上限，而这段是纯映射，无副作用。
 *
 * 关键约定：正文**只放用户输入**，附件走结构化的 [MessageAttachment] 通道 ——
 * 气泡据此渲染文件卡片；附件清单由 HarnessApiMapper.renderUserContent 在发给模型时展开，
 * 模型照样拿得到沙箱绝对路径。把清单拼进正文会让气泡整段当文本渲染（"发文件变成文字"）。
 */
internal data class ComposerPayload(
    val text: String,
    val imageUrls: List<String>,
    val attachments: List<MessageAttachment>,
)

internal fun prepareComposerPayload(
    context: Context,
    trimmedInput: String,
    attachments: List<ChatAttachment>,
): ComposerPayload {
    val imageUrls = attachments.mapNotNull { it.base64DataUrl }
    // 图片 data URL 只走 imageUrls：再落一份会让 payloadJson 每张图存两份，多图长会话易 OOM。
    val structured = attachments.map {
        MessageAttachment(
            name = it.name,
            isImage = it.isImage,
            sizeBytes = it.sizeBytes,
            guestPath = it.guestFilePath ?: "/attachments/${it.name}",
            imageUrl = null,
            mimeType = it.mimeType,
        )
    }
    // 纯附件（正文为空）时补一句极简提示，避免模型收到空 user 消息。
    val text = when {
        trimmedInput.isNotBlank() -> trimmedInput
        structured.any { !it.isImage } -> context.getString(R.string.chat_attachment_files_prompt)
        else -> context.getString(R.string.chat_attachment_images_prompt)
    }
    return ComposerPayload(text, imageUrls, structured)
}
