package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.diagnostics.RequestContextSnapshot
import top.tianyan.app.harness.diagnostics.RequestContextDiff
import top.tianyan.app.harness.diagnostics.RequestArchiveStatus
import top.tianyan.app.ui.components.RuntimeTextButton
import java.text.DateFormat
import java.util.Date

@Composable
fun RequestContextDialog(requests: List<RequestContextSnapshot>, archiveStatus: RequestArchiveStatus, onDismiss: () -> Unit) {
    var selected by remember(requests) { mutableStateOf(requests.lastIndex.coerceAtLeast(0)) }
    val snapshot = requests.getOrNull(selected)
    val previous = requests.getOrNull(selected - 1)
    val diff = remember(previous, snapshot) {
        if (previous != null && snapshot != null) RequestContextDiff.between(previous, snapshot) else null
    }
    var query by remember { mutableStateOf("") }
    ChatBottomSheet(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_request_context_title)) },
        confirmButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_request_context_close)) }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.chat_request_context_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(when (archiveStatus) {
                    RequestArchiveStatus.MEMORY_ONLY -> R.string.chat_request_archive_memory
                    RequestArchiveStatus.LOADING -> R.string.chat_request_archive_loading
                    RequestArchiveStatus.SAVING -> R.string.chat_request_archive_saving
                    RequestArchiveStatus.SAVED -> R.string.chat_request_archive_saved
                    RequestArchiveStatus.ERROR -> R.string.chat_request_archive_error
                }), style = MaterialTheme.typography.bodySmall,
                    color = if (archiveStatus == RequestArchiveStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (snapshot == null) {
                    Text(stringResource(R.string.chat_request_context_empty))
                } else {
                    requests.forEachIndexed { index, request ->
                        RuntimeTextButton(onClick = { selected = index }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(
                                    R.string.chat_request_context_attempt,
                                    request.operationId.take(8), request.round, request.attempt,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(request.capturedAt)),
                                ),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.chat_request_context_metadata, snapshot.protocol, snapshot.bodyBytes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (diff != null) {
                        Text(
                            stringResource(R.string.chat_request_context_diff_summary,
                                diff.added.size, diff.removed.size, diff.changed.size, diff.unchangedCount),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(stringResource(R.string.chat_request_context_diff_notice), style = MaterialTheme.typography.bodySmall)
                        if (!diff.complete) Text(stringResource(R.string.chat_request_context_diff_partial),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (diff.added.isNotEmpty()) Text(stringResource(R.string.chat_request_context_diff_added, diff.added.joinToString(", ")))
                        if (diff.removed.isNotEmpty()) Text(stringResource(R.string.chat_request_context_diff_removed, diff.removed.joinToString(", ")))
                        if (diff.changed.isNotEmpty()) Text(stringResource(R.string.chat_request_context_diff_changed, diff.changed.joinToString(", ")))
                    }
                    if (snapshot.previewTruncated) {
                        Text(
                            stringResource(R.string.chat_request_context_clipped),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text(stringResource(R.string.chat_request_context_search)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    val matches = remember(snapshot, query) {
                        snapshot.sections.filter { it.label.contains(query, true) || it.preview.contains(query, true) }
                    }
                    if (matches.isEmpty()) Text(stringResource(R.string.chat_request_context_no_matches))
                    matches.forEach { section ->
                        var expanded by remember(snapshot, section.label) { mutableStateOf(false) }
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                RuntimeTextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        stringResource(
                                            if (expanded) R.string.chat_request_context_collapse else R.string.chat_request_context_expand,
                                            section.label,
                                        ),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (expanded) SelectionContainer {
                                    Column {
                                        val previousSection = previous?.sections?.firstOrNull { it.label == section.label }
                                            ?.takeIf { section.label in diff?.changed.orEmpty() }
                                        if (previousSection != null) {
                                            Text(stringResource(R.string.chat_request_context_previous), style = MaterialTheme.typography.labelMedium)
                                            Text(previousSection.preview, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                            if (previousSection.previewTruncated) Text(stringResource(R.string.chat_request_context_clipped),
                                                style = MaterialTheme.typography.bodySmall)
                                            Text(stringResource(R.string.chat_request_context_current), style = MaterialTheme.typography.labelMedium)
                                        }
                                        Text(section.preview, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        if (section.previewTruncated) Text(stringResource(R.string.chat_request_context_clipped),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
