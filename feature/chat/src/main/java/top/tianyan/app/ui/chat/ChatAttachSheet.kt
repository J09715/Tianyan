package top.tianyan.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * "+" 附件弹窗（参考 rikkahub FilesPicker）：顶部附件宫格（相册图片 / 草图 / 文件），
 * 下方分段入口（扩展管理、压缩对话）。拍照/音视频暂不提供，需要时按格扩展。
 */
@Composable
internal fun ChatAttachSheet(
    canCompress: Boolean,
    onCamera: () -> Unit,
    onPickImage: () -> Unit,
    onSketch: () -> Unit,
    onPickFile: () -> Unit,
    onOpenSkillsMcp: () -> Unit,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    ChatBottomSheet(
        onDismissRequest = onDismiss,
        title = { Text("添加内容", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 附件宫格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AttachGridCell("拍照", RuntimeIconName.Camera, Modifier.weight(1f), onCamera)
                    AttachGridCell("图片", RuntimeIconName.Image, Modifier.weight(1f), onPickImage)
                    AttachGridCell("草图", RuntimeIconName.Edit, Modifier.weight(1f), onSketch)
                    AttachGridCell("文件", RuntimeIconName.Document, Modifier.weight(1f), onPickFile)
                }

                // 分段入口
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AttachListEntry(
                        icon = RuntimeIconName.Prompt,
                        title = "扩展管理",
                        subtitle = "技能与 MCP 工具挂载",
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        onClick = onOpenSkillsMcp,
                    )
                    AttachListEntry(
                        icon = RuntimeIconName.Compress,
                        title = "压缩对话",
                        subtitle = if (canCompress) "把较早的历史折叠为结构化摘要，原文可回读"
                        else "当前会话还没有可折叠的历史",
                        enabled = canCompress,
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                        onClick = onCompact,
                    )
                }
            }
        },
    )
}

@Composable
private fun AttachGridCell(
    label: String,
    icon: RuntimeIconName,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.heightIn(min = 84.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RuntimeIcon(icon, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AttachListEntry(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RuntimeIcon(
                icon,
                Modifier.size(20.dp),
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
