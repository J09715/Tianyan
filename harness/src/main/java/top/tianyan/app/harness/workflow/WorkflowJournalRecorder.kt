package top.tianyan.app.harness.workflow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.model.workflow.*

/** The awaited sink is a durability barrier, not a UI progress listener. */
internal class WorkflowJournalRecorder(
    private val state: MutableStateFlow<WorkflowRuntimeState>,
    private val sink: suspend (WorkflowRuntimeState) -> Unit,
) {
    private val lock = Mutex()

    suspend fun checkpoint() = lock.withLock { sink(state.value) }

    suspend fun started(nodeId: String) = record(nodeId, null)
    suspend fun settled(nodeId: String, output: NodeExecutionOutput, expansion: WorkflowExpansion? = null) = record(nodeId, output, expansion)

    private suspend fun record(nodeId: String, output: NodeExecutionOutput?, expansion: WorkflowExpansion? = null) = lock.withLock {
        currentCoroutineContext().ensureActive()
        require(expansion == null || output?.status == NodeRunStatus.SUCCESS) { "只有成功审批可以保存扩图" }
        require(state.value.journal.size < 10_000) { "执行日志超过 10000 条，请另起一次运行" }
        state.update { current ->
            val previous = current.journal.lastOrNull { it.nodeId == nodeId }
            val attempt = if (output == null) (previous?.attempt ?: 0) + 1 else previous?.attempt ?: 1
            val entry = WorkflowJournalEntry(current.journal.size + 1, nodeId, attempt,
                if (output == null) WorkflowJournalKind.STARTED else WorkflowJournalKind.SETTLED,
                System.currentTimeMillis(), output, expansion)
            val context = current.context
            val definition = expansion?.let { WorkflowExpansions.append(current.definition, nodeId, it) } ?: current.definition
            current.copy(
                definition = definition,
                baseDefinition = if (expansion == null) current.baseDefinition else current.baseDefinition ?: current.definition,
                journal = current.journal + entry,
                context = if (output == null) context else context?.copy(
                    nodeOutputs = context.nodeOutputs + (nodeId to output),
                    globalVariables = context.globalVariables + output.variables,
                ),
                nodeStates = if (output == null) current.nodeStates else current.nodeStates + (nodeId to
                    WorkflowNodeRunState(output.status, output.error ?: output.textOutput.lineSequence().lastOrNull().orEmpty(), output)) +
                    expansion?.nodes.orEmpty().associate { it.id to WorkflowNodeRunState(NodeRunStatus.PENDING) },
            )
        }
        sink(state.value)
        currentCoroutineContext().ensureActive()
    }
}
