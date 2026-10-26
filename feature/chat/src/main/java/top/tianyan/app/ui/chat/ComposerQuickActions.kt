package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.*

/** Common actions occupy one toolbar; the attachment menu keeps every action reachable. */
@Composable
internal fun ComposerQuickActions(
    activeModel: AiModelEntity?,
    onOpenModels: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    approvalMode: ApprovalMode,
    onOpenApprovalModes: () -> Unit,
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RuntimeIconButton(
            onClick = onOpenModels,
            modifier = Modifier.size(48.dp),
            contentDescription = stringResource(R.string.chat_select_model),
        ) {
            ProviderBadge(activeModel?.provider.orEmpty(), size = 26.dp)
        }
        val modeLabel = stringResource(when (approvalMode) {
            ApprovalMode.FULL_ACCESS -> R.string.chat_approval_full_access
            ApprovalMode.ASSISTED -> R.string.chat_approval_assisted
            ApprovalMode.REQUEST -> R.string.chat_approval_request
        })
        val modeColor = when (approvalMode) {
            ApprovalMode.FULL_ACCESS -> MaterialTheme.colorScheme.error
            ApprovalMode.ASSISTED -> MaterialTheme.colorScheme.primary
            ApprovalMode.REQUEST -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        RuntimeIconButton(
            onClick = onOpenApprovalModes,
            modifier = Modifier.size(48.dp),
            contentDescription = modeLabel,
        ) {
            RuntimeIcon(RuntimeIconName.Shield, Modifier.size(22.dp), modeColor)
        }
        Box {
            RuntimeIconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp),
                contentDescription = stringResource(R.string.chat_add_attachment)) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_send_image)) },
                    leadingIcon = { RuntimeIcon(RuntimeIconName.Image) }, onClick = { showMenu = false; onPickImage() })
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_send_file)) },
                    leadingIcon = { RuntimeIcon(RuntimeIconName.Document) }, onClick = { showMenu = false; onPickFile() })
            }
        }
    }
}
