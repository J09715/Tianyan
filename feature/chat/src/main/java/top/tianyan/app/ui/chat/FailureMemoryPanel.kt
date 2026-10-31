package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.operation.FailureMemoryAccess
import top.tianyan.app.harness.operation.FailureMemoryKind
import top.tianyan.app.harness.operation.FailureMemoryState
import top.tianyan.app.ui.components.RuntimeTextButton
import java.text.DateFormat
import java.util.Date

@Composable
internal fun FailureMemoryPanel(sessionId: String, access: FailureMemoryAccess) {
    var error by remember(sessionId) { mutableStateOf(false) }
    var busy by remember(sessionId) { mutableStateOf(false) }
    val recordsFlow = remember(sessionId, access) {
        access.observe(sessionId).flowOn(Dispatchers.IO).catch { error = true; emit(null) }
    }
    val state by recordsFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val delete: (FailureMemoryState) -> Unit = { reviewed ->
        scope.launch {
            busy = true
            error = false
            try {
                withContext(Dispatchers.IO) { access.delete(sessionId, reviewed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = true
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chat_failure_memory_title, state?.records?.size ?: 0),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!state?.records.isNullOrEmpty()) {
                RuntimeTextButton(onClick = { state?.let(delete) }, enabled = !busy) {
                    Text(stringResource(R.string.chat_confirm_clear), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(stringResource(R.string.chat_failure_memory_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error) Text(stringResource(R.string.chat_failure_memory_error), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (state?.records.isNullOrEmpty() && !error) {
            Text(stringResource(R.string.chat_failure_memory_empty), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state?.let { reviewed ->
            reviewed.records.asReversed().forEach { record ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(record.toolName, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            RuntimeTextButton(onClick = { delete(reviewed.copy(records = listOf(record))) }, enabled = !busy) {
                                Text(stringResource(R.string.chat_confirm_delete), maxLines = 1)
                            }
                        }
                        val kind = when (record.kind) {
                            FailureMemoryKind.EXECUTION_FAILED -> R.string.chat_failure_memory_execution
                            FailureMemoryKind.POLICY_BLOCKED -> R.string.chat_failure_memory_policy
                            FailureMemoryKind.READ_INTERRUPTED -> R.string.chat_failure_memory_interrupted
                            FailureMemoryKind.OUTCOME_UNKNOWN -> R.string.chat_failure_memory_unknown
                        }
                        Text(stringResource(R.string.chat_failure_memory_details, stringResource(kind), record.attempts,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(record.expiresAt))),
                            style = MaterialTheme.typography.bodySmall)
                        SelectionContainer {
                            Text(stringResource(R.string.chat_failure_memory_source, record.signature, record.callEntryId, record.resultEntryId),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
