package top.tianyan.app.harness.workflow

import kotlinx.coroutines.delay
import top.tianyan.app.core.model.workflow.NodeExecutionOutput
import top.tianyan.app.core.model.workflow.NodeRunStatus
import top.tianyan.app.core.model.workflow.WorkflowNode
import top.tianyan.app.core.model.workflow.WorkflowNodeType
import top.tianyan.app.core.model.workflow.WorkflowRuntimeContext

class DelayNodeExecutor : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.DELAY)

    override suspend fun execute(
        node: WorkflowNode, context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput {
        val millis = if ("milliseconds" in node.config) {
            interpolate(node.config.getValue("milliseconds"), context).trim().toLongOrNull()
                ?.also { require(it in 0L..600_000L) { "等待时间必须在 0–600000ms" } }
                ?: error("等待毫秒数必须为整数")
        } else {
            val seconds = interpolate(node.config["seconds"] ?: node.config["delaySeconds"] ?: "1", context)
                .trim().toDoubleOrNull() ?: error("等待秒数必须是数字")
            require(seconds.isFinite() && seconds in 0.0..600.0) { "等待时间必须在 0–600 秒" }
            (seconds * 1000).toLong()
        }
        onProgress(NodeRunStatus.RUNNING, "等待 ${millis}ms…")
        delay(millis)
        return NodeExecutionOutput(
            status = NodeRunStatus.SUCCESS, textOutput = "已等待 ${millis}ms",
            variables = mapOf("DELAY_SECONDS" to (millis / 1000.0).toString(), "DELAY_MS" to millis.toString()),
            durationMs = millis,
        )
    }
}
