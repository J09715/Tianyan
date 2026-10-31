package top.tianyan.app.ui.bot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeTextButton

/** 从 GroupInfoDialog 抽出以控制文件尺寸：背景画刷、背景风格行、管理员移交与统一权限设置。 */

@Composable
internal fun groupBackgroundBrush(style: String?): Brush = when (style) {
    "warm" -> Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
            MaterialTheme.colorScheme.surface,
        ),
    )
    "mint" -> Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.09f),
            MaterialTheme.colorScheme.surface,
        ),
    )
    "night" -> Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surface,
        ),
    )
    else -> SolidColor(Color.Transparent)
}

internal val BACKGROUND_STYLES = listOf("default", "warm", "mint", "night")

@Composable
internal fun BackgroundStyleRow(background: String, onSelect: (String) -> Unit) {
    Text(
        stringResource(R.string.fbot_background),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BACKGROUND_STYLES.forEach { style ->
            RuntimeFilterChip(
                selected = background == style,
                onClick = { onSelect(style) },
                label = { Text(backgroundLabel(style)) },
            )
        }
    }
}

@Composable
private fun backgroundLabel(style: String): String = when (style) {
    "warm" -> stringResource(R.string.fbot_background_warm)
    "mint" -> stringResource(R.string.fbot_background_mint)
    "night" -> stringResource(R.string.fbot_background_night)
    else -> stringResource(R.string.fbot_background_default)
}

/**
 * 群统一审批模式（DS Bot"全员统一沙盒预设"）：群维度成员会话统一对齐，
 * 覆盖成员个人默认权限；只有用户可设置。
 */
@Composable
internal fun UnifiedApprovalModeRow(selected: String?, onSelect: (String?) -> Unit) {
    Text(
        "成员统一权限",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to "完全访问（默认）", ApprovalMode.ASSISTED.id to "询问后执行", ApprovalMode.REQUEST.id to "每次审批")
            .forEach { (mode, label) ->
                RuntimeFilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(label) },
                )
            }
    }
}

/** 群管理员移交：新管理员必须是本群成员；不允许留无管理员的群。 */
@Composable
internal fun GroupAdminTransferDialog(
    members: List<Bot>,
    currentAdminId: String,
    onTransfer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移交管理员") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "移交后原管理员转为普通成员，群聊由新管理员调度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                members.filter { it.id != currentAdminId }.forEach { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${member.name}（${member.role}）", modifier = Modifier.padding(top = 12.dp))
                        RuntimeTextButton(onClick = { onTransfer(member.id) }) { Text("移交") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) }
        },
    )
}
