package top.tianyan.app.harness.workflow

import java.util.UUID
import top.tianyan.app.core.model.workflow.*

internal data class ExpansionApproval(val output: NodeExecutionOutput, val patch: WorkflowExpansion? = null)

internal suspend fun approveExpansion(
    node: WorkflowNode,
    context: WorkflowRuntimeContext,
    definition: WorkflowDefinition,
    broker: WorkflowApprovalBroker,
    onProgress: suspend (NodeRunStatus, String) -> Unit,
): ExpansionApproval {
    val source = node.config.getValue(WorkflowExpansions.SOURCE_KEY)
    val output = context.nodeOutputs[source]
    require(output?.status == NodeRunStatus.SUCCESS && source in context.upstreamNodeIds.orEmpty()) { "扩图结果源未成功执行或未激活" }
    val patch = WorkflowExpansions.decode(requireNotNull(output.structuredResult) { "扩图结果源没有结构化结果" })
    WorkflowExpansions.append(definition, node.id, patch)
    val depth = WorkflowExpansions.depth(definition, node.id)
    val nextStages = patch.nodes.count { WorkflowExpansions.SOURCE_KEY in it.config }
    val review = buildString {
        appendLine("以下步骤将追加到本次运行。确认后可能执行命令、调用智能体或修改文件，请核对全部配置。变量引用按后续执行时的上下文解析。")
        appendLine("工作区：${context.workspacePath}")
        appendLine("第 $depth / ${WorkflowExpansions.MAX_DEPTH} 层扩图。")
        if (nextStages > 0) appendLine("包含 $nextStages 个后续扩图审批；本次确认只覆盖以下步骤，后续提案仍需分别确认。")
        appendLine(node.description)
        appendLine("完整提案（含节点类型、配置、失败策略和连接）：")
        append(WorkflowExpansions.encode(patch))
    }
    onProgress(NodeRunStatus.WAITING_APPROVAL, "等待确认新增 ${patch.nodes.size} 个步骤")
    val decision = broker.await(WorkflowApprovalRequest(context.executionId, node.id,
        "确认第 $depth 层新增 ${patch.nodes.size} 个步骤", review, reviewToken = UUID.randomUUID().toString()))
    return if (decision.approved) ExpansionApproval(NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "已确认新增 ${patch.nodes.size} 个步骤"), patch)
    else ExpansionApproval(NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 130, error = "用户拒绝扩图"))
}
