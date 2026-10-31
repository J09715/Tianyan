package top.tianyan.app.harness.bot

import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.effects.ToolRecoveryNotice
import top.tianyan.app.harness.subagent.subagentOutcomeUncertainty

/** Only the stopped native operation's observations can establish a safe blocked outcome. */
internal fun assignmentEffectsUncertain(messages: List<HarnessMessage>, anchorId: String?): Boolean {
    val anchor = anchorId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
    if (anchorId != null && anchor < 0) return true
    val owned = messages.drop(anchor + 1)
    return owned.filterIsInstance<ToolResult>().any { it.errorCode == ToolRecoveryNotice.OUTCOME_UNKNOWN } ||
        subagentOutcomeUncertainty(owned).isNotEmpty()
}
