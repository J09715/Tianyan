package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupReplyMode
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun GroupInfoDialog(
    group: GroupChat,
    members: List<top.tianyan.app.core.model.bot.Bot> = emptyList(),
    deleting: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        description: String,
        background: String,
        replyMode: GroupReplyMode,
        unifiedApprovalMode: String?,
    ) -> Unit,
    onDelete: () -> Unit,
    onManageMembers: () -> Unit,
    onClearMessages: () -> Unit,
    onManageTeam: () -> Unit = {},
    onManageBudget: () -> Unit = {},
    onManageGovernance: () -> Unit = {},
    onImportTeam: () -> Unit = {},
    onTransferAdmin: (String) -> Unit = {},
) {
    var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
    var description by rememberSaveable(group.id) { mutableStateOf(group.description) }
    var background by rememberSaveable(group.id) {
        mutableStateOf(group.background.ifBlank { "default" })
    }
    var replyMode by rememberSaveable(group.id) { mutableStateOf(group.replyMode) }
    var unifiedApprovalMode by rememberSaveable(group.id) { mutableStateOf(group.unifiedApprovalMode) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showTransfer by rememberSaveable { mutableStateOf(false) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fbot_group_info)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.fbot_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(200) },
                    label = { Text(stringResource(R.string.fbot_group_desc)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.fbot_reply_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupReplyMode.entries.forEach { mode ->
                        RuntimeFilterChip(
                            selected = replyMode == mode,
                            onClick = { replyMode = mode },
                            label = { Text(replyModeLabel(mode)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.fbot_reply_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackgroundStyleRow(background) { background = it }
                UnifiedApprovalModeRow(unifiedApprovalMode) { unifiedApprovalMode = it }
                HorizontalDivider(Modifier.padding(vertical = 4.dp, horizontal = 0.dp))
                RuntimeTextButton(onClick = onManageMembers, enabled = !deleting && group.teamGroupId == null) {
                    Text(stringResource(R.string.fbot_manage_members))
                }
                RuntimeTextButton(
                    onClick = { showTransfer = true },
                    enabled = !deleting && group.teamGroupId == null && members.count { it.id != group.adminBotId } > 0,
                ) { Text("移交管理员") }
                RuntimeTextButton(onClick = onImportTeam, enabled = !deleting) { Text("引用团队组") }
                RuntimeTextButton(onClick = onManageGovernance, enabled = !deleting) { Text("本群审议记录") }
                RuntimeTextButton(onClick = onManageTeam, enabled = !deleting) {
                    Text("查看团队架构")
                }
                RuntimeTextButton(onClick = onManageBudget, enabled = !deleting) {
                    Text(stringResource(R.string.fbot_budget_title))
                }
                RuntimeTextButton(onClick = onClearMessages, enabled = !deleting) {
                    Text(stringResource(R.string.fbot_clear_messages))
                }
                RuntimeTextButton(onClick = { confirmDelete = true }, enabled = !deleting) {
                    Text(
                        text = stringResource(R.string.fbot_group_disband),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onSave(name, description, background, replyMode, unifiedApprovalMode) },
                enabled = name.isNotBlank() && !deleting,
            ) { Text(stringResource(R.string.fbot_save)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) }
        },
    )
    if (showTransfer) {
        GroupAdminTransferDialog(
            members = members,
            currentAdminId = group.adminBotId,
            onTransfer = {
                showTransfer = false
                onTransferAdmin(it)
            },
            onDismiss = { showTransfer = false },
        )
    }
    if (confirmDelete) {
        RuntimeAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.fbot_group_disband)) },
            text = { Text(stringResource(R.string.fbot_group_disband_confirm)) },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    enabled = !deleting,
                ) { Text(stringResource(R.string.fbot_delete)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.fbot_cancel))
                }
            },
        )
    }
}

@Composable
private fun replyModeLabel(mode: GroupReplyMode): String = when (mode) {
    GroupReplyMode.ADMIN_FIRST -> stringResource(R.string.fbot_reply_mode_admin)
    GroupReplyMode.ALL_MEMBERS -> stringResource(R.string.fbot_reply_mode_all)
}
