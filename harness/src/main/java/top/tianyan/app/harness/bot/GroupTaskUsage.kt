package top.tianyan.app.harness.bot

import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.core.database.UsageLedgerSessionRow
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.model.bot.BotAssignment

/** Aggregate ledger scalars for the original task tree, without loading conversation payloads. */
class GroupTaskUsage(
    private val assignments: BotAssignmentRepository,
    private val runtime: HarnessRuntimeRepository,
    private val budgets: top.tianyan.app.core.database.repository.TaskBudgetRepository? = null,
) {
    suspend fun describe(id: String): String {
        val root = assignments.get(id) ?: return "任务不存在，无法核对用量"
        val group = root.groupId ?: return "旧版共享会话任务无法独立归因用量"
        if (!root.independent) return "旧版共享会话任务无法独立归因用量"
        val tasks = assignments.observeForGroup(group).first()
        val ids = linkedSetOf(root.id)
        var added: Boolean
        do {
            val next = tasks.filter { it.parentAssignmentId in ids }.map { it.id }
            added = ids.addAll(next)
        } while (added)
        val selected = (tasks.filter { it.id in ids } + root).distinctBy { it.id }
        val sessions = selected.filter { it.independent }.mapNotNull { it.sessionId }.distinct()
        val ledger = sessions.associateWith { session ->
            runtime.aggregateUsageLedgerBySession(null, null, null, null, session)
        }
        val own = root.sessionId?.let { ledger[it] }.orEmpty()
        val all = ledger.values.flatten()
        val budget = root.sessionId?.let { budgets?.forSession(it) }
        return buildString {
            budget?.let { appendLine("共享委派预算：额度 ${it.limitTokens}（0 不限）／已结算 ${it.usedTokens}／预留 ${it.heldTokens}；未知请求 ${it.unknownRequests}。预算含已拦截的压缩和重试，与下面原会话账本口径不同。") }
            appendLine("【任务树已入账 token】")
            appendLine("当前任务：${render(own)}")
            appendLine("含后代 ${selected.size} 个任务／${sessions.size} 个独立会话：${render(all)}")
            val withoutLedger = selected.count { task -> task.sessionId == null || ledger[task.sessionId].isNullOrEmpty() }
            if (withoutLedger > 0) appendLine("$withoutLedger 个任务尚无账本记录，不能据此认定零消耗。")
            append("来源为持久用量账本；重试和续办保留在原会话累计。推理与缓存按供应商口径分列，不再次加到输入/输出；未回传或未落账用量不可推断。这不是费用结算或硬预算。")
        }
    }

    private fun render(rows: List<UsageLedgerSessionRow>): String =
        if (rows.isEmpty()) "暂无已入账用量"
        else "输入 ${rows.sumOf { it.inputTokens }}／输出 ${rows.sumOf { it.outputTokens }}／" +
            "推理 ${rows.sumOf { it.reasoningTokens }}／缓存读 ${rows.sumOf { it.cacheReadTokens }}／" +
            "缓存写 ${rows.sumOf { it.cacheWriteTokens }}（${rows.sumOf { it.calls }} 次已入账调用）"
}
