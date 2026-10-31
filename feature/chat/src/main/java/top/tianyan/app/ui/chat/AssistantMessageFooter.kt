package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.ui.components.*

@Composable
internal fun AssistantMessageFooter(message: AssistantText, prominent: Boolean, showRegenerate: Boolean,
    onRegenerate: () -> Unit, onCreateBranch: () -> Unit, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = if (prominent) 4.dp else 0.dp, bottom = if (prominent) 8.dp else 0.dp)) {
        AssistantNerdLine(message)
        Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!prominent) Spacer(Modifier.weight(1f))
            RuntimeIconButton(onClick = onCopy, modifier = Modifier.size(if (prominent) 32.dp else 24.dp),
                contentDescription = stringResource(R.string.chat_copy)) {
                RuntimeIcon(RuntimeIconName.Copy, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RuntimeIconButton(onClick = onCreateBranch, modifier = Modifier.size(if (prominent) 32.dp else 24.dp),
                contentDescription = stringResource(R.string.chat_branch_from_here)) {
                RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (prominent) {
                if (showRegenerate) RuntimeIconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp),
                    contentDescription = stringResource(R.string.chat_regenerate)) {
                    RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MessageFeedbackAction(message.id)
            }
        }
    }
}
