package top.tianyan.app.harness.workflow

import top.tianyan.app.core.model.workflow.NodeExecutionOutput
import top.tianyan.app.core.model.workflow.NodeRunStatus
import top.tianyan.app.core.model.workflow.WorkflowNode
import top.tianyan.app.core.model.workflow.WorkflowNodeType
import top.tianyan.app.core.model.workflow.WorkflowRuntimeContext

interface NodeExecutor {
    val supportedTypes: Set<WorkflowNodeType>

    suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput
}

