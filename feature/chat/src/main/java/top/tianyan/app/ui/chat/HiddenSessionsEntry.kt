package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeTextButton

/** 已隐藏 Bot 会话的入口与恢复弹窗；隐藏只影响抽屉展示，不删历史。 */
@Composable
internal fun HiddenSessionsEntry(
    hiddenSessions: List<HarnessSessionEntity>,
    onRestore: (String, Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        RuntimeTextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.chat_hidden_sessions, hiddenSessions.size))
        }
    }
    if (expanded) {
        RuntimeAlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(stringResource(R.string.chat_hidden_sessions_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    hiddenSessions.forEach { session ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            RuntimeTextButton(onClick = { onRestore(session.id, false) }) {
                                Text(stringResource(R.string.chat_restore_session))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        hiddenSessions.forEach { onRestore(it.id, false) }
                        expanded = false
                    },
                ) { Text(stringResource(R.string.chat_restore_all_sessions)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { expanded = false }) {
                    Text(stringResource(R.string.chat_close))
                }
            },
        )
    }
}
