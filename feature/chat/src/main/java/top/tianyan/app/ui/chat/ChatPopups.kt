package top.tianyan.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.theme.Radius
import top.tianyan.app.ui.theme.Spacing

/** 输入框上方的斜杠指令 / @能力 快捷弹窗。 */
@Composable
internal fun SlashCommandPopup(
    commands: List<SlashCommandItem>,
    onSelect: (SlashCommandItem) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = Radius.smShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
            commands.forEach { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(cmd) }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    RuntimeIcon(cmd.icon, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        cmd.command,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        cmd.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MentionPopup(
    mentions: List<MentionItem>,
    onSelect: (MentionItem) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = Radius.mdShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 10.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.chat_capability_mount),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.chat_enabled_for_request),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            LazyColumn(
                modifier = Modifier.heightIn(max = 240.dp),
            ) {
                items(mentions.size, key = { mentions[it].id }) { index ->
                    val item = mentions[index]
                    val isSkill = item.type == MentionType.SKILL
                    val tagBg = if (isSkill) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                    val tagBorder = if (isSkill) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                    val tagColor = if (isSkill) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.tertiary

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) }
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        // 左侧图标芯片
                        Surface(
                            shape = Radius.xsShape,
                            color = tagBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, tagBorder),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                RuntimeIcon(
                                    item.icon,
                                    Modifier.size(17.dp),
                                    tint = tagColor,
                                )
                            }
                        }

                        // 中间文案与描述
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                Text(
                                    "@${item.name}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Surface(
                                    color = tagBg,
                                    border = androidx.compose.foundation.BorderStroke(0.8.dp, tagBorder),
                                    shape = Radius.xsShape,
                                ) {
                                    Text(
                                        if (isSkill) stringResource(R.string.chat_skill_badge) else stringResource(R.string.chat_mcp_badge),
                                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                        color = tagColor,
                                    )
                                }
                            }
                            Text(
                                item.description,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

