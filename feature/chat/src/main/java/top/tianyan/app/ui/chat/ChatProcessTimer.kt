package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R

internal fun formatProcessDuration(ms: Long): String = top.tianyan.app.ui.components.formatRuntimeProcessDuration(ms)

@Composable
internal fun ChatProcessTimer(id: String, running: Boolean, startedAtMs: Long? = null, durationMs: Long? = null) =
    top.tianyan.app.ui.components.RuntimeProcessTimer(id, running, startedAtMs, durationMs)

@Composable
internal fun ChatReasoningLabel(id: String, live: Boolean, durationMs: Long?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (live) R.string.chat_deep_reasoning else R.string.chat_reasoning_process),
            modifier = Modifier.weight(1f).then(chatProcessPulse(live)), style = MaterialTheme.typography.bodySmall,
            color = chatProcessColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        ChatProcessTimer(id, live, durationMs = durationMs)
    }
}
