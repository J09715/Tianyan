package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.MarkdownText
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.components.StreamingMarkdownText
import top.tianyan.app.core.model.ChunkedText

@Composable
internal fun ExpandableGroupText(
    messageId: String,
    text: ChunkedText,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (streaming) StreamingMarkdownText(text, modifier, "group:$messageId")
    else ExpandableGroupText(messageId, text.toString(), false, modifier)
}

@Composable
internal fun ExpandableGroupText(
    messageId: String,
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    markdown: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val collapsible = !streaming && (text.length > 1200 || text.count { it == '\n' } >= 14)
    Column(modifier) {
        if (collapsible && !expanded) {
            // Plain preview avoids parsing a truncated code fence or Markdown table.
            Text(text.take(1200), maxLines = 8, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = color)
        } else if (markdown) {
            MarkdownText(text)
        } else {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        if (collapsible) {
            RuntimeTextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.fbot_message_collapse else R.string.fbot_message_expand))
            }
        }
    }
}
