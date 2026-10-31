package top.tianyan.app.core.model.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class WorkflowExpansion(val nodes: List<WorkflowNode>, val edges: List<WorkflowEdge>)

/** Append-only proposals: no definition metadata, existing node, or existing edge can be changed. */
object WorkflowExpansions {
    const val SOURCE_KEY = "expansionResultNode"
    const val MAX_DEPTH = 4
    private val json = Json { encodeDefaults = true }
    private val allowed = WorkflowNodeType.entries.toSet() - setOf(WorkflowNodeType.TRIGGER,
        WorkflowNodeType.PROCESS_SERVICE, WorkflowNodeType.HOST_ACTION)

    fun decode(result: JsonObject): WorkflowExpansion {
        require(result.toString().length <= 65_536) { "扩图提案超过 64 KiB" }
        return json.decodeFromJsonElement(WorkflowExpansion.serializer(), result)
    }

    fun encode(patch: WorkflowExpansion): String = json.encodeToString(WorkflowExpansion.serializer(), patch)

    /** Namespaces are append-only; a nested approval always lives under its parent approval ID. */
    fun depth(definition: WorkflowDefinition, anchorId: String): Int = 1 + definition.nodes.count {
        SOURCE_KEY in it.config && anchorId.startsWith("${it.id}.")
    }

    fun append(definition: WorkflowDefinition, anchorId: String, patch: WorkflowExpansion): WorkflowDefinition {
        val anchor = definition.nodes.singleOrNull { it.id == anchorId }
        require(anchor?.type == WorkflowNodeType.HUMAN_APPROVAL && !anchor.config[SOURCE_KEY].isNullOrBlank()) { "扩图必须来自配置了结果源的人工审批节点" }
        val depth = depth(definition, anchorId)
        require(depth <= MAX_DEPTH) { "动态扩图最多 $MAX_DEPTH 层" }
        require(encode(patch).length <= 65_536) { "扩图提案超过 64 KiB" }
        require(patch.nodes.size in 1..32 && patch.edges.size in 1..128) { "每次扩图需要 1–32 个节点、1–128 条边" }
        require(definition.nodes.size + patch.nodes.size <= 256 && definition.edges.size + patch.edges.size <= 1024) { "扩图后的工作流超过容量限制" }
        val existing = definition.nodes.map { it.id }.toSet()
        val added = patch.nodes.map { it.id }.toSet()
        require(patch.nodes.all { it.id !in existing && it.id.startsWith("$anchorId.") &&
            it.id.removePrefix("$anchorId.").matches(Regex("[A-Za-z0-9_-]{1,64}")) }) { "新增节点 ID 必须使用审批节点 ID 加点号前缀，且不能复用已有 ID" }
        require(patch.nodes.all { it.type in allowed }) { "扩图不支持触发器、托管进程或宿主动作" }
        patch.nodes.filter { SOURCE_KEY in it.config }.forEach { next ->
            require(depth < MAX_DEPTH) { "第 $MAX_DEPTH 层只能追加普通步骤，不能再创建扩图审批" }
            require(next.type == WorkflowNodeType.HUMAN_APPROVAL && next.config[SOURCE_KEY] in added) { "下一阶段扩图审批必须引用同一提案内的结果源" }
        }
        require(patch.edges.all { it.toNodeId in added && (it.fromNodeId in added ||
            it.fromNodeId == anchorId && it.fromPort == "success" && it.conditionExpression.isNullOrBlank()) }) { "新增边只能连接新节点，入口必须来自审批节点的 success 端口" }
        val reachable = mutableSetOf(anchorId)
        var changed: Boolean
        do {
            changed = false
            patch.edges.forEach { if (it.fromNodeId in reachable && reachable.add(it.toNodeId)) changed = true }
        } while (changed)
        require(added.all { it in reachable }) { "所有新增节点都必须由审批节点可达" }
        val expanded = definition.copy(nodes = definition.nodes + patch.nodes, edges = definition.edges + patch.edges)
        val issues = WorkflowValidator.validate(expanded)
        require(issues.isEmpty()) { issues.joinToString { it.message } }
        return expanded
    }

    /** Replay approvals to verify that the persisted graph has exactly the approved additions. */
    fun validateHistory(state: WorkflowRuntimeState) {
        val expansions = state.journal.filter { it.expansion != null }
        if (expansions.isEmpty()) {
            require(state.baseDefinition == null) { "扩图历史缺少审批记录" }
            return
        }
        var graph = requireNotNull(state.baseDefinition) { "扩图历史缺少原始定义" }
        val applied = mutableSetOf<String>()
        val outputs = mutableMapOf<String, NodeExecutionOutput>()
        state.journal.forEach { entry ->
            require(graph.nodes.any { it.id == entry.nodeId }) { "节点在扩图确认前已有执行记录" }
            if (entry.expansion == null) {
                entry.output?.let { outputs[entry.nodeId] = it }
                return@forEach
            }
            require(entry.kind == WorkflowJournalKind.SETTLED && entry.output?.status == NodeRunStatus.SUCCESS && applied.add(entry.nodeId)) { "扩图审批记录无效或重复" }
            val source = graph.nodes.single { it.id == entry.nodeId }.config.getValue(SOURCE_KEY)
            val proposal = outputs[source]
            require(proposal?.status == NodeRunStatus.SUCCESS && proposal.structuredResult?.let(::decode) == entry.expansion) { "审批记录与结果源提案不一致" }
            graph = append(graph, entry.nodeId, requireNotNull(entry.expansion))
            outputs[entry.nodeId] = requireNotNull(entry.output)
        }
        require(graph == state.definition) { "历史定义与已确认扩图不一致" }
    }
}
