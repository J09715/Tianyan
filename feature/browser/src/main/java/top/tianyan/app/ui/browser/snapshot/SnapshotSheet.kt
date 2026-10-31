package top.tianyan.app.ui.browser.snapshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
fun SnapshotSheet(state: SnapshotSheetState, onDismiss: () -> Unit) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Snapshot · ${state.title.ifBlank { state.url }}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.snapshot.interactiveRefs, key = { it.ref }) { ref ->
                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentPadding = PaddingValues(10.dp),
                        ) {
                            val parts = buildString {
                                append("[${ref.ref}]")
                                append(" <${ref.tag}")
                                ref.type?.let { append(" type='$it'") }
                                append(">")
                                ref.text?.takeIf { it.isNotBlank() }?.let { append(" $it") }
                            }
                            Text(
                                parts,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text("关闭", color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}
