package top.tianyan.app.harness.workflow

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import top.tianyan.app.core.model.workflow.*

/** Only awaited, durable completions enter the dependency frontier. UI snapshots may be ahead. */
internal suspend fun dispatchWorkflowDag(
    state: MutableStateFlow<WorkflowRuntimeState>,
    initialContext: WorkflowRuntimeContext,
    execute: suspend (WorkflowNode, WorkflowRuntimeContext) -> NodeExecutionOutput,
): String? = coroutineScope {
    var context = initialContext
    val running = mutableMapOf<String, Job>()
    val results = Channel<Pair<String, NodeExecutionOutput>>(Channel.BUFFERED)
    try {
        while (true) {
            val graph = state.value.definition
            val nodes = graph.nodes.associateBy { it.id }
            val incoming = graph.edges.groupBy { it.toNodeId }
            val pending = nodes.keys - context.nodeOutputs.keys - running.keys
            if (pending.isEmpty() && running.isEmpty()) break
            val resolvable = pending.filter { id -> incoming[id].orEmpty().all { it.fromNodeId in context.nodeOutputs } }
            var workers = running.keys.count { nodes.getValue(it).type != WorkflowNodeType.HUMAN_APPROVAL }
            var skipped = false
            resolvable.forEach { id ->
                val activeEdges = incoming[id].orEmpty().filter { edge ->
                    context.nodeOutputs[edge.fromNodeId]?.let { WorkflowEdgeCondition.matches(edge, it) } == true
                }
                if (incoming[id].orEmpty().isNotEmpty() && activeEdges.isEmpty()) {
                    val output = NodeExecutionOutput(NodeRunStatus.SKIPPED, textOutput = "上游分支未选择此节点")
                    context = context.withDagOutput(id, output)
                    state.update { current -> current.copy(
                        context = current.context?.withDagOutput(id, output),
                        nodeStates = current.nodeStates + (id to WorkflowNodeRunState(output.status, output.textOutput, output)),
                    ) }
                    skipped = true
                } else {
                    val needsSlot = nodes.getValue(id).type != WorkflowNodeType.HUMAN_APPROVAL
                    if (needsSlot && workers >= graph.maxParallelNodes) return@forEach
                    if (needsSlot) workers++
                    val nodeContext = context.copy(upstreamNodeIds = activeEdges.map { it.fromNodeId }.distinct())
                    running[id] = launch { results.send(id to execute(nodes.getValue(id), nodeContext)) }
                }
            }
            if (skipped) continue
            check(running.isNotEmpty()) { "调度器无法继续，请检查工作流依赖" }
            val (id, output) = results.receive()
            running.remove(id)?.join()
            context = context.withDagOutput(id, output)
            if (output.status == NodeRunStatus.FAILED && nodes.getValue(id).failurePolicy != FailurePolicy.CONTINUE) {
                running.values.forEach { it.cancel() }
                running.values.toList().joinAll()
                return@coroutineScope output.error ?: "节点 $id 执行失败"
            }
        }
        state.update { it.copy(context = context) }
        null
    } finally {
        results.cancel()
    }
}

private fun WorkflowRuntimeContext.withDagOutput(id: String, output: NodeExecutionOutput) = copy(
    nodeOutputs = nodeOutputs + (id to output), globalVariables = globalVariables + output.variables,
)
