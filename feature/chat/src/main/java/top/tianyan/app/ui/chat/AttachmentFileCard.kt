package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.harness.MessageAttachment
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * 文件附件卡片：把用户发的文件显示成可点击的卡片，而不是埋在正文里的一行路径。
 *
 * 为什么需要它：附件此前只在发送时被拼进提示词文本，气泡层只渲染 `message.text`，
 * 于是用户看到的是一整段带路径的文字——也就是"发文件变成文字"。现在附件元信息
 * 随消息持久化（[MessageAttachment]），这里渲染成卡片；提示词那侧保留挂载说明是
 * 为了告诉模型去哪里读文件，两者职责不同、不冲突。
 *
 * 卡片给出三件事：文件名、大小、沙箱内绝对路径。路径显示出来是因为模型要用 base
 * 读它，用户看到路径也知道文件挂载到了哪里（而不是"发上去就没了"）。
 */
@Composable
internal fun AttachmentFileCard(
    attachment: MessageAttachment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isArchive = isArchiveName(attachment.name)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // 触摸目标下限 48dp，对齐 UI 铁律
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    RuntimeIcon(
                        name = if (isArchive) RuntimeIconName.Compress else RuntimeIconName.File,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 归档类额外提示"需先解压"：模型拿到 zip 后第一步该做解压，
                    // 不提示的话它可能把 zip 当文本读出一堆乱码。
                    text = AttachmentHelper.formatFileSize(attachment.sizeBytes) +
                        if (isArchive) " · 压缩包（需先用 base 解压）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (attachment.guestPath.isNotBlank()) {
                    Text(
                        text = attachment.guestPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 需要提示"先解压"的归档后缀。 */
private val ARCHIVE_EXTENSIONS = listOf(".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".rar", ".7z")

internal fun isArchiveName(name: String): Boolean =
    ARCHIVE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
