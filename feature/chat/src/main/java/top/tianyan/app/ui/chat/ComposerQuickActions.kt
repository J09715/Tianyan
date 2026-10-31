package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.ModelBadge
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName

/** Common actions occupy one toolbar; the "+" opens the attach sheet (grid + extensions + compact). */
@Composable
internal fun ComposerQuickActions(
    activeModel: AiModelEntity?,
    onOpenModels: () -> Unit,
    onOpenAttachSheet: () -> Unit,
    approvalMode: ApprovalMode,
    onOpenApprovalModes: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RuntimeIconButton(
            onClick = onOpenModels,
            modifier = Modifier.size(48.dp),
            contentDescription = stringResource(R.string.chat_select_model),
        ) {
            ModelBadge(
                modelId = activeModel?.model,
                providerIdOrName = activeModel?.provider.orEmpty(),
                size = 26.dp,
            )
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
        RuntimeIconButton(
            onClick = onOpenAttachSheet,
            modifier = Modifier.size(48.dp),
            contentDescription = stringResource(R.string.chat_add_attachment),
        ) {
            RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
