package top.tianyan.app.ui.workflow

import java.util.UUID
import top.tianyan.app.core.model.workflow.*

internal fun blankWorkflowDefinition() = WorkflowDefinition(
    id = "workflow_${UUID.randomUUID().toString().take(8)}", name = "新工作流",
    description = "在画布中添加并连接节点", category = "自定义",
    nodes = listOf(
        WorkflowNode("start", WorkflowNodeType.TRIGGER, "手动触发", canvasX = 80f, canvasY = 160f),
        WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "完成", canvasX = 420f, canvasY = 160f),
    ), edges = listOf(WorkflowEdge("edge_start_done", "start", "output", "done")),
)
