package top.tianyan.app.core.model.workflow

data class WorkflowValidationIssue(val field: String, val message: String)

object WorkflowValidator {
    fun validate(definition: WorkflowDefinition): List<WorkflowValidationIssue> {
        val issues = mutableListOf<WorkflowValidationIssue>()
        if (definition.id.isBlank()) issues += WorkflowValidationIssue("id", "工作流 ID 不能为空")
        if (definition.name.isBlank()) issues += WorkflowValidationIssue("name", "工作流名称不能为空")
        if (definition.maxParallelNodes !in 1..8) issues += WorkflowValidationIssue("maxParallelNodes", "最大执行并发必须在 1–8 之间")
        if (definition.nodes.isEmpty()) issues += WorkflowValidationIssue("nodes", "工作流至少需要一个节点")

        val nodeIds = definition.nodes.map { it.id }
        if (nodeIds.size != nodeIds.distinct().size) {
            issues += WorkflowValidationIssue("nodes", "节点 ID 必须唯一")
        }
        definition.nodes.forEach { node ->
            if (node.type == WorkflowNodeType.PLUGIN_ACTION &&
                (node.config["plugin"].orEmpty().matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+")) != true ||
                    !node.config["extension"].orEmpty().matches(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")))) {
                issues += WorkflowValidationIssue("nodes.${node.id}.config", "插件动作需要有效的 plugin 与 extension 身份")
            }
            if (node.type == WorkflowNodeType.HUMAN_APPROVAL) {
                WorkflowApprovalTimeout.validationError(node.config[WorkflowApprovalTimeout.CONFIG_KEY])?.let {
                    issues += WorkflowValidationIssue("nodes.${node.id}.config.timeoutSeconds", it)
                }
            }
            node.config[WorkflowExpansions.SOURCE_KEY]?.let { source ->
                val sourceNode = definition.nodes.singleOrNull { it.id == source }
                if (node.type != WorkflowNodeType.HUMAN_APPROVAL || sourceNode?.type !in setOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.SUBAGENT_DELEGATE) ||
                    sourceNode?.config?.get(WorkflowResultSchema.CONFIG_KEY).isNullOrBlank() ||
                    definition.edges.none { it.fromNodeId == source && it.toNodeId == node.id && it.fromPort == "success" && it.conditionExpression.isNullOrBlank() }) {
                    issues += WorkflowValidationIssue("nodes.${node.id}.expansionResultNode", "扩图审批必须通过 success 边直接连接带 resultSchema 的智能体结果源")
                }
                if (node.config["requestedVariables"].orEmpty().isNotBlank()) issues += WorkflowValidationIssue("nodes.${node.id}", "扩图审批不能同时修改变量")
            }
            node.config[WorkflowResultSchema.CONFIG_KEY]?.let { raw ->
                val error = if (node.type !in setOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.SUBAGENT_DELEGATE))
                    "只有智能体节点支持 resultSchema"
                else runCatching { WorkflowResultSchema.parse(raw) }.exceptionOrNull()?.message
                error?.let { issues += WorkflowValidationIssue("nodes.${node.id}.resultSchema", it) }
            }
            if (node.id.isBlank()) issues += WorkflowValidationIssue("nodes", "节点 ID 不能为空")
            if (node.title.isBlank()) issues += WorkflowValidationIssue("nodes.${node.id}.title", "节点标题不能为空")
            if (node.timeoutSeconds !in 1..3600) {
                issues += WorkflowValidationIssue("nodes.${node.id}.timeoutSeconds", "超时必须在 1–3600 秒之间")
            }
            if (node.type == WorkflowNodeType.CONDITION_BRANCH) {
                val expression = node.config["expression"] ?: node.config["condition"]
                WorkflowConditionEvaluator.validationError(expression)?.let { message ->
                    issues += WorkflowValidationIssue("nodes.${node.id}.expression", message)
                }
            }
            if (node.type == WorkflowNodeType.HOST_ACTION) {
                issues += VirtualScreenWorkflowValidation.issues(node)
                val action = node.config["action"].orEmpty().ifBlank { "status" }
                val def = HostWorkflowActions.find(action)
                if (def == null && node.config["command"].isNullOrBlank()) {
                    issues += WorkflowValidationIssue(
                        "nodes.${node.id}.action",
                        "未知宿主动作：$action（可改用已知动作，或提供 command 作为逃逸舱）",
                    )
                }
                def?.fields?.filter { it.required }?.forEach { field ->
                    val missing = if (action == "virtual_screen_input_text" && field.key == "text")
                        node.config[field.key].isNullOrEmpty() else node.config[field.key].isNullOrBlank()
                    if (missing) {
                        issues += WorkflowValidationIssue(
                            "nodes.${node.id}.${field.key}",
                            "宿主动作「${def.label}」缺少必填参数：${field.label}",
                        )
                    }
                }
            }
            if (node.type == WorkflowNodeType.DELAY) {
                val seconds = node.config["seconds"] ?: node.config["delaySeconds"]
                if (seconds != null && !seconds.contains("\${") &&
                    seconds.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..600.0 } != true) {
                    issues += WorkflowValidationIssue("nodes.${node.id}.seconds", "等待秒数必须在 0–600 之间")
                }
                node.config["milliseconds"]?.takeUnless { it.contains("\${") }?.let { raw ->
                    if (raw.toLongOrNull()?.let { it in 0L..600_000L } != true) {
                        issues += WorkflowValidationIssue("nodes.${node.id}.milliseconds", "等待毫秒数必须为 0–600000 的整数")
                    }
                }
            }
        }

        val known = nodeIds.toSet()
        val edgeIds = definition.edges.map { it.id }
        if (edgeIds.size != edgeIds.distinct().size) {
            issues += WorkflowValidationIssue("edges", "连接 ID 必须唯一")
        }
        definition.edges.forEach { edge ->
            if (edge.id.isBlank()) issues += WorkflowValidationIssue("edges", "连接 ID 不能为空")
            if (edge.fromNodeId !in known || edge.toNodeId !in known) {
                issues += WorkflowValidationIssue("edges.${edge.id}", "边引用了不存在的节点")
            }
            if (edge.fromNodeId == edge.toNodeId) {
                issues += WorkflowValidationIssue("edges.${edge.id}", "节点不能连接到自身")
            }
            WorkflowEdgeCondition.validationError(edge)?.let { message ->
                issues += WorkflowValidationIssue("edges.${edge.id}.condition", message)
            }
        }

        if (known.isNotEmpty() && containsCycle(definition)) {
            issues += WorkflowValidationIssue("edges", "工作流必须是无环图（DAG）")
        }
        return issues
    }

    private fun containsCycle(definition: WorkflowDefinition): Boolean {
        val indegree = definition.nodes.associate { it.id to 0 }.toMutableMap()
        val outgoing = definition.edges.groupBy { it.fromNodeId }
        definition.edges.forEach { edge ->
            if (edge.toNodeId in indegree && edge.fromNodeId in indegree) {
                indegree[edge.toNodeId] = (indegree[edge.toNodeId] ?: 0) + 1
            }
        }
        val ready = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            visited++
            outgoing[id].orEmpty().forEach { edge ->
                if (edge.toNodeId in indegree) {
                    val next = (indegree[edge.toNodeId] ?: 1) - 1
                    indegree[edge.toNodeId] = next
                    if (next == 0) ready.add(edge.toNodeId)
                }
            }
        }
        return visited != definition.nodes.size
    }
}
