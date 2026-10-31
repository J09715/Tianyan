package top.tianyan.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.theme.semanticColors

/**
 * 运行事件 → 视觉描述（非红队事件）。
 *
 * 从 ChatWorkbenchPanels 抽出：该文件已到行数棘轮上限，而这段是纯映射、
 * 与面板布局无耦合。红队事件在 [redTeamEventVisual] 里前置拦截。
 */
@Composable
internal fun baseEventVisual(event: HarnessEvent): EventVisual = when (event) {
    is HarnessEvent.OperationStarted -> EventVisual(
        RuntimeIconName.Play, MaterialTheme.colorScheme.primary,
        stringResource(R.string.chat_event_operation_started),
        stringResource(R.string.chat_operation_lane, event.laneName),
    )
    is HarnessEvent.OperationFinished -> EventVisual(
        if (event.outcome == "completed") RuntimeIconName.Check else RuntimeIconName.Stop,
        if (event.outcome == "completed") semanticColors().success else MaterialTheme.colorScheme.error,
        if (event.outcome == "completed") stringResource(R.string.chat_event_operation_completed) else stringResource(R.string.chat_event_operation_ended),
        event.detail ?: event.outcome,
    )
    is HarnessEvent.ProviderRoundStarted -> EventVisual(
        RuntimeIconName.Brain, semanticColors().agent,
        stringResource(R.string.chat_event_provider_round, event.round + 1),
        buildString {
            append(stringResource(R.string.chat_attempt_count, event.attempt + 1))
            event.modelId?.let { append(" · $it") }
        },
    )
    is HarnessEvent.ProviderRoundSettled -> EventVisual(
        RuntimeIconName.Sparkles, semanticColors().agent,
        stringResource(R.string.chat_event_provider_settled),
        stringResource(R.string.chat_token_usage, event.inputTokens, event.outputTokens),
    )
    is HarnessEvent.ToolCallStarted -> EventVisual(
        RuntimeIconName.Wrench, MaterialTheme.colorScheme.tertiary,
        stringResource(R.string.chat_event_tool_started, event.toolName),
        stringResource(R.string.chat_event_tool_executing),
    )
    is HarnessEvent.ToolCallSettled -> EventVisual(
        if (event.success) RuntimeIconName.Check else RuntimeIconName.Alert,
        if (event.success) semanticColors().success else MaterialTheme.colorScheme.error,
        if (event.success) stringResource(R.string.chat_event_tool_completed, event.toolName)
        else stringResource(R.string.chat_event_tool_failed, event.toolName),
        event.durationMs?.let { stringResource(R.string.chat_event_duration, formatPanelDuration(it)) }
            ?: stringResource(R.string.chat_event_settled),
    )
    is HarnessEvent.ApprovalRequested -> EventVisual(
        RuntimeIconName.Shield, semanticColors().warning,
        stringResource(R.string.chat_event_approval),
        "${event.toolName} · ${event.riskLevel}",
    )
    is HarnessEvent.RecoveryApplied -> EventVisual(
        RuntimeIconName.Refresh, MaterialTheme.colorScheme.secondary,
        stringResource(R.string.chat_event_recovery),
        event.detail ?: event.outcome,
    )
    is HarnessEvent.PermissionRequired -> EventVisual(
        RuntimeIconName.Shield, semanticColors().warning,
        stringResource(R.string.chat_event_permission_required, event.permission),
        event.reason,
    )
    is HarnessEvent.PlanStepProgress -> EventVisual(
        when (event.status) {
            "COMPLETED" -> RuntimeIconName.Check
            "FAILED" -> RuntimeIconName.Alert
            "RUNNING" -> RuntimeIconName.Play
            else -> RuntimeIconName.Sparkles
        },
        when (event.status) {
            "COMPLETED" -> semanticColors().success
            "FAILED" -> MaterialTheme.colorScheme.error
            "RUNNING" -> MaterialTheme.colorScheme.primary
            else -> semanticColors().agent
        },
        "工序 ${event.stepId} · ${event.title}",
        event.resultSummary ?: "状态: ${event.status}",
    )
    else -> EventVisual(RuntimeIconName.Info, MaterialTheme.colorScheme.onSurfaceVariant, "运行事件", event.javaClass.simpleName)
}