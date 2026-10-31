package top.tianyan.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.theme.semanticColors

/** 单条会话项：只保留标题一行；重命名/删除走长按，当前与执行中的行额外给出 ⋮。 */
@Composable
internal fun SessionDrawerItem(
    session: HarnessSessionEntity, isCurrent: Boolean, runState: SessionRunState,
    onSwitch: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onHide: (() -> Unit)? = null,
) {
    var showMenu by remember(session.id) { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.chat_more_controls)
    val statusLabel = stringResource(when (runState) {
        SessionRunState.RUNNING -> R.string.chat_step_running
        SessionRunState.WAITING_APPROVAL -> R.string.chat_step_waiting
        SessionRunState.FAILED -> R.string.chat_step_failed
        else -> R.string.chat_sidebar_idle
    })
    val isBusy = runState == SessionRunState.RUNNING || runState == SessionRunState.WAITING_APPROVAL ||
        runState == SessionRunState.FAILED
    val statusColor = when (runState) {
        SessionRunState.RUNNING -> semanticColors().warning
        SessionRunState.WAITING_APPROVAL -> semanticColors().agent
        SessionRunState.FAILED -> MaterialTheme.colorScheme.error
        else -> Color.Transparent
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp).clip(MaterialTheme.shapes.large)
                .combinedClickable(onClick = onSwitch, onLongClick = { showMenu = true })
                .semantics { selected = isCurrent; stateDescription = statusLabel }
                .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                if (isCurrent || isBusy) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { showMenu = true }
                            .semantics { contentDescription = moreLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(RuntimeIconName.More, Modifier.size(16.dp),
                            if (isBusy) statusColor else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_rename_session)) },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.Edit, Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onRename() },
                    )
                    if (onHide != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_hide_session)) },
                            leadingIcon = { RuntimeIcon(RuntimeIconName.VisibilityOff, Modifier.size(18.dp)) },
                            onClick = { showMenu = false; onHide() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_delete_session), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.Trash, Modifier.size(18.dp), MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                    )
                }
            }
        }
    }
}
