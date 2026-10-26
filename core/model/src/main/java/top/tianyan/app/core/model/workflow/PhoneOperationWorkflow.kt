package top.tianyan.app.core.model.workflow

data class PhoneWorkflowOperation(
    val action: String,
    val parameters: Map<String, String> = emptyMap(),
    val waitMs: Long = 0,
)

/** Builds a strict success-only chain. Input contents become editable run variables. */
object PhoneOperationWorkflow {
    fun create(id: String, name: String, operations: List<PhoneWorkflowOperation>): WorkflowDefinition {
        val variables = linkedMapOf<String, String>()
        var inputIndex = 0
        val nodes = buildList {
            add(WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始"))
            add(WorkflowNode("screen", WorkflowNodeType.HOST_ACTION, "创建虚拟屏",
                config = mapOf("action" to "virtual_screen_ensure")))
            operations.forEachIndexed { index, operation ->
                val def = VirtualScreenWorkflowActions.all.firstOrNull { it.id == operation.action }
                    ?: error("不支持录制动作：${operation.action}")
                require(operation.waitMs in 0..600_000) { "等待时间必须在 0–600000ms" }
                val parameters = operation.parameters.toMutableMap()
                if (operation.action in setOf("virtual_screen_set_text", "virtual_screen_input_text")) {
                    val key = "TEXT_${++inputIndex}"
                    variables[key] = parameters["text"].orEmpty()
                    parameters["text"] = "\${$key}"
                }
                val wait = if (operation.action == "virtual_screen_wait")
                    parameters["duration_ms"]?.toLongOrNull() ?: 1000 else 0
                require(wait in 0..600_000) { "等待时间必须在 0–600000ms" }
                add(WorkflowNode(
                    "step_${index + 1}", WorkflowNodeType.HOST_ACTION, "${index + 1}. ${def.label}",
                    config = parameters + mapOf("action" to operation.action, "wait_ms" to operation.waitMs.toString()),
                    timeoutSeconds = ((wait + operation.waitMs) / 1000 + 30).toInt(),
                    failurePolicy = FailurePolicy.ABORT,
                ))
            }
            add(WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "操作步骤已发出，请核对页面"))
        }
        return WorkflowLayout.arrange(WorkflowDefinition(
            id, name.trim().ifBlank { "手机操作工作流" },
            description = "从手机模型操作记录生成。点击、滑动、输入和等待均可编辑；重放前需要相同应用页面，输入发出不代表业务操作成功。",
            category = "虚拟屏", nodes = nodes,
            edges = nodes.zipWithNext().mapIndexed { index, (from, to) ->
                WorkflowEdge("edge_$index", from.id, "success", to.id)
            },
            defaultVariables = variables,
        ))
    }
}
