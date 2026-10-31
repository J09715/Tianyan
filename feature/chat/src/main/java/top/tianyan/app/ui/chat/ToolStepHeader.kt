package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.*
import top.tianyan.app.ui.components.*
import top.tianyan.app.ui.theme.semanticColors

@Composable
internal fun ToolStepHeader(call: ToolCall, result: ToolResult?, running: Boolean) {
    val state = toolStepState(result, running)
    val stateText = stringResource(toolStateLabel(state))
    val tint = when (state) {
        ToolStepState.FAILED, ToolStepState.UNKNOWN -> MaterialTheme.colorScheme.error
        ToolStepState.WAITING, ToolStepState.DEFERRED -> semanticColors().warning
        ToolStepState.SUCCEEDED, ToolStepState.RUNNING, ToolStepState.INCOMPLETE -> chatProcessColor()
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 2.dp, vertical = 4.dp)
        .semantics { stateDescription = stateText },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatProcessIcon(toolIcon(call.tool), running = state == ToolStepState.RUNNING)
        val target = toolStepTarget(call)
        val title = toolStepTitle(call)
        Text(if (target == null) title else "$title · $target", Modifier.weight(1f).then(chatProcessPulse(state == ToolStepState.RUNNING)),
            style = MaterialTheme.typography.bodySmall, color = chatProcessColor(),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state != ToolStepState.SUCCEEDED && state != ToolStepState.RUNNING) Text(stateText,
            style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        val diff = remember(call, result) {
            if (call.tool == HarnessTool.WRITE || call.tool == HarnessTool.EDIT) parseDiffStat(call, result) else null
        }
        diff?.let { (added, deleted) -> DiffStatBadge(added, deleted) }
        val duration = result?.durationMs ?: result?.let { (it.createdAt - call.createdAt).coerceAtLeast(0) }
        ChatProcessTimer(call.id, state == ToolStepState.RUNNING, call.createdAt, duration)
        RuntimeIcon(RuntimeIconName.ChevronRight,
            Modifier.size(16.dp), chatProcessColor())
    }
}

internal fun toolStateLabel(state: ToolStepState): Int = when (state) {
    ToolStepState.RUNNING -> R.string.chat_step_running
    ToolStepState.WAITING -> R.string.chat_step_waiting
    ToolStepState.DEFERRED -> R.string.chat_step_deferred
    ToolStepState.SUCCEEDED -> R.string.chat_step_succeeded
    ToolStepState.FAILED -> R.string.chat_step_failed
    ToolStepState.UNKNOWN -> R.string.chat_step_unknown
    ToolStepState.INCOMPLETE -> R.string.chat_step_incomplete
}

@Composable
private fun toolStepTitle(call: ToolCall): String = when (call.tool) {
    HarnessTool.MCP -> toolDisplayName(call)
    HarnessTool.BASE -> stringResource(commandTitleResource(call))
    else -> stringResource(when (call.tool) {
        HarnessTool.READ -> R.string.chat_action_read
        HarnessTool.WRITE -> R.string.chat_action_write
        HarnessTool.EDIT -> R.string.chat_action_edit
        HarnessTool.BASE -> R.string.chat_action_command
        HarnessTool.PROCESS -> R.string.chat_action_process
        HarnessTool.HOST -> R.string.chat_action_host
        HarnessTool.DOWNLOAD -> R.string.chat_action_download
        HarnessTool.MEMORY -> R.string.chat_action_memory
        HarnessTool.PLAN -> R.string.chat_action_plan
        HarnessTool.SCRATCHPAD -> R.string.chat_action_scratchpad
        HarnessTool.HISTORY_SEARCH -> R.string.chat_action_search
        HarnessTool.HISTORY_READ -> R.string.chat_action_history
        HarnessTool.BUILD_SCRIPT -> R.string.chat_action_build_script
        HarnessTool.SUBAGENT -> R.string.chat_action_subagent
        HarnessTool.LOAD_RULE -> R.string.chat_action_rule
        HarnessTool.LOAD_SKILL -> R.string.chat_action_skill
        HarnessTool.COMPRESS -> R.string.chat_action_compress
        HarnessTool.ASK_USER -> R.string.chat_action_question
        HarnessTool.RENDER_SURFACE -> R.string.chat_action_surface
        HarnessTool.RED_TEAM -> R.string.chat_action_command
        HarnessTool.MCP -> error("Handled above")
    })
}

/** Deliberately exclude command bodies, file content, URLs and arbitrary JSON from the collapsed summary. */
internal fun toolStepTarget(call: ToolCall): String? {
    val key = when (call.tool) {
        HarnessTool.READ, HarnessTool.WRITE, HarnessTool.EDIT -> "path"
        HarnessTool.LOAD_SKILL, HarnessTool.LOAD_RULE -> "name"
        HarnessTool.PROCESS -> "id"
        else -> return null
    }
    return (call.args[key] as? JsonPrimitive)?.contentOrNull?.take(512)
        ?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')
        ?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(100)?.takeIf { it.isNotBlank() }
}
