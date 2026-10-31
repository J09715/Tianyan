package top.tianyan.app.core.model.workflow

import kotlinx.serialization.Serializable

@Serializable
enum class WorkflowJournalKind { STARTED, SETTLED, INTERRUPTED }

@Serializable
data class WorkflowJournalEntry(
    val sequence: Int,
    val nodeId: String,
    val attempt: Int,
    val kind: WorkflowJournalKind,
    val timestamp: Long,
    val output: NodeExecutionOutput? = null,
    val expansion: WorkflowExpansion? = null,
)

data class WorkflowRecoveryPlan(
    val cachedOutputs: Map<String, NodeExecutionOutput> = emptyMap(),
    val retryNodeIds: Set<String> = emptySet(),
    val pendingNodeIds: Set<String> = emptySet(),
    val blockedReason: String? = null,
) {
    val canResume: Boolean get() = blockedReason == null
}

/** Resume only snapshots with write-ahead execution evidence; never infer safety from UI status. */
object WorkflowRecovery {
    fun inspect(state: WorkflowRuntimeState): WorkflowRecoveryPlan {
        fun blocked(reason: String) = WorkflowRecoveryPlan(blockedReason = reason)
        if (state.status !in setOf(WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED)) return blocked("只有失败或已取消的运行可以继续")
        if (state.journalVersion != 1 || state.context == null) return blocked("旧记录缺少执行前日志，请使用整流程重跑")
        if (state.context.executionId != state.executionId || state.context.workflowId != state.definition.id) return blocked("运行上下文与历史定义不一致")
        if (WorkflowValidator.validate(state.definition).isNotEmpty()) return blocked("历史工作流定义无效")
        runCatching { WorkflowExpansions.validateHistory(state) }.exceptionOrNull()?.let { return blocked(it.message ?: "扩图历史无效") }
        val nodes = state.definition.nodes.associateBy { it.id }
        // These resources do not survive process death; cached creation/start is not reusable.
        if (nodes.values.any { it.type == WorkflowNodeType.PROCESS_SERVICE ||
                it.type == WorkflowNodeType.HOST_ACTION &&
                (it.config["action"].orEmpty().startsWith("virtual_screen_") || it.config["action"] == "gui_pilot") }) {
            return blocked("此流程依赖托管进程或屏幕会话，请重新运行以重建资源")
        }
        val latest = mutableMapOf<String, WorkflowJournalEntry>()
        state.journal.forEachIndexed { index, entry ->
            if (entry.sequence != index + 1 || entry.nodeId !in nodes || entry.attempt < 1) return blocked("执行日志不完整，无法安全继续")
            val previous = latest[entry.nodeId]
            when (entry.kind) {
                WorkflowJournalKind.STARTED -> {
                    if (entry.output != null || entry.attempt != (previous?.attempt ?: 0) + 1 || previous?.kind == WorkflowJournalKind.STARTED)
                        return blocked("执行日志的尝试顺序无效")
                }
                WorkflowJournalKind.SETTLED -> {
                    if (previous?.kind != WorkflowJournalKind.STARTED || previous.attempt != entry.attempt ||
                        entry.output?.status !in setOf(NodeRunStatus.SUCCESS, NodeRunStatus.FAILED)) return blocked("执行日志缺少对应的开始或结果")
                }
                WorkflowJournalKind.INTERRUPTED -> {
                    if (previous?.kind != WorkflowJournalKind.STARTED || previous.attempt != entry.attempt || entry.output != null)
                        return blocked("执行日志的中断记录无效")
                }
            }
            latest[entry.nodeId] = entry
        }
        if (state.journal.size + latest.values.count { it.kind == WorkflowJournalKind.STARTED } >= 10_000) return blocked("执行日志容量已满，请另起一次运行")
        val cached = linkedMapOf<String, NodeExecutionOutput>()
        val retry = linkedSetOf<String>()
        nodes.forEach { (id, node) ->
            val entry = latest[id]
            if (WorkflowExpansions.SOURCE_KEY in node.config && entry?.output?.status == NodeRunStatus.SUCCESS && entry.expansion == null)
                return blocked("扩图审批缺少已确认的新增步骤")
            if (entry?.kind in setOf(WorkflowJournalKind.STARTED, WorkflowJournalKind.INTERRUPTED)) {
                if (node.type !in setOf(WorkflowNodeType.HUMAN_APPROVAL, WorkflowNodeType.DELAY, WorkflowNodeType.CONDITION_BRANCH, WorkflowNodeType.TRIGGER, WorkflowNodeType.TERMINAL_OUTPUT, WorkflowNodeType.SET_VARIABLE)) retry += id
            } else if (entry?.output != null) {
                if (entry.output.status == NodeRunStatus.SUCCESS || node.failurePolicy == FailurePolicy.CONTINUE) cached[id] = entry.output
                else retry += id
            }
        }
        // Cached results must not depend on an operation we are about to retry.
        val downstream = retry.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            state.definition.edges.forEach { if (it.fromNodeId in downstream && downstream.add(it.toNodeId)) changed = true }
        } while (changed)
        if (cached.keys.any { it in downstream }) return blocked("重试节点已有已完成的后继，请整流程重跑并核对副作用")
        if (nodes.keys.all { it in cached || state.context.nodeOutputs[it]?.status == NodeRunStatus.SKIPPED })
            return blocked("所有节点已有结果，没有可继续执行的步骤")
        return WorkflowRecoveryPlan(cached, retry, nodes.keys - cached.keys)
    }
}
