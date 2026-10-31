package top.tianyan.app.harness.subagent

import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.effects.ToolRecoveryNotice
import top.tianyan.app.harness.effects.ToolReplayPolicy
import top.tianyan.app.harness.operation.ReplayPolicy

/** Keep uncertainty visible even when a later unrelated failure becomes the digest's last step. */
internal fun subagentOutcomeUncertainty(transcript: List<HarnessMessage>): String {
    val latestResults = transcript.filterIsInstance<ToolResult>().associateBy { it.toolCallId }
    val uncertain = transcript.filterIsInstance<ToolCall>().any { call ->
        val result = latestResults[call.id]
        result?.errorCode == ToolRecoveryNotice.OUTCOME_UNKNOWN ||
            (result == null && ToolReplayPolicy.forTool(call.tool, call.rawToolName) != ReplayPolicy.SAFE)
    }
    return if (uncertain) "\n\n${ToolRecoveryNotice.unknown().output}" else ""
}
