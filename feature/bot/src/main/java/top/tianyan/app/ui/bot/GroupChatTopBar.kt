package top.tianyan.app.ui.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.*

@Composable
internal fun GroupChatTopBar(
    viewMode: GroupChatViewMode,
    groupName: String,
    statusText: String,
    busy: Boolean,
    workSummary: GroupWorkSummary,
    exporting: Boolean,
    onBack: () -> Unit,
    onSwitchMode: (GroupChatViewMode) -> Unit,
    onExportDiagnostics: () -> Unit,
    onShowInfo: () -> Unit,
) {
    if (viewMode == GroupChatViewMode.STUDIO) {
        RuntimeTopBar(
            title = stringResource(R.string.fbot_cyber_studio),
            onBack = { onSwitchMode(GroupChatViewMode.CHAT) },
            statusText = groupName,
            actions = {
                RuntimeTextButton(
                    onClick = { onSwitchMode(GroupChatViewMode.CHAT) },
                    modifier = Modifier.testTag("topbar-back-to-chat-btn"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Chat, Modifier.size(16.dp))
                        Text(stringResource(R.string.fbot_cyber_studio_back_chat))
                    }
                }
                RuntimeIconButton(
                    onClick = onExportDiagnostics,
                    enabled = !exporting,
                    contentDescription = stringResource(R.string.fbot_export_diagnostics),
                ) {
                    if (exporting) {
                        RuntimeCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(20.dp))
                    }
                }
                RuntimeIconButton(
                    onClick = onShowInfo,
                    contentDescription = stringResource(R.string.fbot_group_info),
                ) {
                    RuntimeIcon(RuntimeIconName.Info, Modifier.size(21.dp))
                }
            },
        )
    } else {
        RuntimeTopBar(
            title = groupName.ifBlank { stringResource(R.string.fbot_loading) },
            onBack = onBack,
            statusText = statusText,
            actions = {
                Surface(
                    onClick = { onSwitchMode(GroupChatViewMode.STUDIO) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(end = 4.dp).testTag("topbar-cyber-studio-btn"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Workspace,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.fbot_cyber_studio),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (busy || workSummary.attention > 0) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        if (workSummary.attention > 0) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                }
                RuntimeIconButton(
                    onClick = onExportDiagnostics,
                    enabled = !exporting,
                    contentDescription = stringResource(R.string.fbot_export_diagnostics),
                ) {
                    if (exporting) {
                        RuntimeCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(20.dp))
                    }
                }
                RuntimeIconButton(
                    onClick = onShowInfo,
                    contentDescription = stringResource(R.string.fbot_group_info),
                ) {
                    RuntimeIcon(RuntimeIconName.Info, Modifier.size(21.dp))
                }
            },
        )
    }
}
