package top.tianyan.app.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.GroupToolStep
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/** Tool execution is a separate disclosure, above and independent of the answer bubble. */
@Composable
internal fun GroupToolCallPanel(messageId: String, steps: List<GroupToolStep>, streaming: Boolean) {
    if (steps.isEmpty()) return
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val succeeded = steps.count { it.success == true }
    val failed = steps.count { it.success == false }
    val pending = steps.size - succeeded - failed
    val summary = buildList {
        if (pending > 0) add(stringResource(if (streaming) R.string.fbot_tool_running_count else R.string.fbot_tool_unknown_count, pending))
        if (succeeded > 0) add(stringResource(R.string.fbot_tool_success_count, succeeded))
        if (failed > 0) add(stringResource(R.string.fbot_tool_failure_count, failed))
    }.joinToString(" · ")
    val action = stringResource(if (expanded) R.string.fbot_steps_collapse else R.string.fbot_steps_expand)
    RuntimeCard(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(vertical = 4.dp).testTag("group-tools:$messageId"),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = action }
                .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag("group-tools-toggle:$messageId"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Wrench, Modifier.size(16.dp),
                if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fbot_tool_calls_title, steps.size), style = MaterialTheme.typography.labelMedium)
                Text(summary, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RuntimeIcon(if (expanded) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown, Modifier.size(18.dp))
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp).testTag("group-tools-details:$messageId")) {
                StepTimeline(steps, streaming)
            }
        }
    }
}
