package top.tianyan.app.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.UUID
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotMemberGroup
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeFilledTonalButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun MemberGroupsDialog(
    groups: List<BotMemberGroup>,
    bots: List<Bot>,
    onDismiss: () -> Unit,
    onSave: suspend (BotMemberGroup) -> Unit,
    onDelete: (BotMemberGroup) -> Unit,
    initialGroupId: String? = null,
    loadImpact: suspend (String) -> List<top.tianyan.app.core.model.bot.TeamUpgradeImpact> = { emptyList() },
    loadVersions: suspend (String) -> List<top.tianyan.app.core.model.bot.TeamPublishedVersion> = { emptyList() },
    onPublish: suspend (String, Int, Set<String>) -> Int = { _, _, _ -> error("发布不可用") },
    onUpgrade: suspend (String, Int, Set<String>) -> Unit = { _, _, _ -> error("升级不可用") },
) {
    var publicationId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingId by rememberSaveable { mutableStateOf<String?>(null) }
    var createdAt by rememberSaveable { mutableStateOf(0L) }
    var pendingDelete by remember { mutableStateOf<BotMemberGroup?>(null) }

    var openedInitial by rememberSaveable(initialGroupId) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(groups, initialGroupId) {
        if (!openedInitial && initialGroupId != null) groups.firstOrNull { it.id == initialGroupId }?.let {
            openedInitial = true; editingId = it.id
        }
    }
    val current = groups.firstOrNull { it.id == editingId } ?: creatingId?.takeIf { it == editingId }?.let { BotMemberGroup(it, "", createdAt) }
    if (current == null) {
        RuntimeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.fbot_member_groups)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.fbot_member_groups_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (groups.isEmpty()) {
                        Text(
                            stringResource(R.string.fbot_member_group_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    creatingId = null
                                    editingId = group.id
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.bodyMedium)
                                RuntimeTextButton(onClick = { publicationId = group.id }) {
                                    Text("发布与升级 · " + if (group.publishedVersion == 0) "尚未发布" else "v${group.publishedVersion}")
                                }
                                Text(
                                    stringResource(R.string.fbot_member_group_member_count, group.memberIds.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RuntimeIconButton(
                                onClick = { pendingDelete = group },
                                contentDescription = stringResource(R.string.fbot_delete),
                            ) {
                                RuntimeIcon(
                                    RuntimeIconName.Trash,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    RuntimeFilledTonalButton(
                        onClick = {
                            createdAt = System.currentTimeMillis()
                            creatingId = UUID.randomUUID().toString()
                            editingId = creatingId
                        },
                    ) {
                        Text(stringResource(R.string.fbot_member_group_new))
                    }
                }
            },
            confirmButton = {
                RuntimeButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_close)) }
            },
        )
    } else {
        TeamGroupEditorDialog(
            group = current,
            bots = bots,
            onDismiss = { editingId = null; creatingId = null },
            onSave = { saved ->
                onSave(saved)
                editingId = null; creatingId = null
            },
        )
    }
    groups.firstOrNull { it.id == publicationId }?.let { group ->
        TeamPublicationDialog(group, { publicationId = null }, loadImpact, loadVersions, onPublish, onUpgrade)
    }
    pendingDelete?.let { group ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.fbot_member_group_delete_title)) },
            text = { Text(stringResource(R.string.fbot_member_group_delete_confirm, group.name)) },
            confirmButton = {
                RuntimeButton(onClick = {
                    onDelete(group)
                    pendingDelete = null
                }) { Text(stringResource(R.string.fbot_delete)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.fbot_cancel))
                }
            },
        )
    }
}
