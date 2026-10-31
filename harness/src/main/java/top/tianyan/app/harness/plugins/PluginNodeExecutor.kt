package top.tianyan.app.harness.plugins

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.model.workflow.*
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.workflow.NodeExecutor
import top.tianyan.app.harness.workflow.WorkflowApprovalBroker

/** Compiled callback with host-owned schema, fresh approval token, status and output formatting. */
class PluginNodeExecutor(private val plugins: HarnessPluginRegistry, private val broker: WorkflowApprovalBroker, private val redactor: SecretRedactor) : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.PLUGIN_ACTION)
    override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext, onProgress: suspend (NodeRunStatus, String) -> Unit): NodeExecutionOutput {
        val pluginId = node.config["plugin"].orEmpty()
        val extensionId = node.config["extension"].orEmpty()
        val extension = plugins.node(pluginId, extensionId) ?: return failed("插件节点不存在或未装配")
        val raw = node.config["arguments"] ?: "{}"
        if (raw.length > 32_768) return failed("插件节点参数过大")
        val arguments = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return failed("插件节点参数必须为 JSON object")
        if (!PluginSchemas.valid(extension.schema, arguments)) return failed("插件节点参数不符合 Schema")
        val token = UUID.randomUUID().toString()
        onProgress(NodeRunStatus.WAITING_APPROVAL, "等待确认插件动作")
        val decision = broker.await(WorkflowApprovalRequest(context.executionId, node.id, node.title,
            redactor.redact("插件 $pluginId/$extensionId\n${extension.description}\n$arguments").take(36_000), reviewToken = token))
        if (!decision.approved) return failed("用户拒绝插件动作")
        currentCoroutineContext().ensureActive()
        onProgress(NodeRunStatus.RUNNING, "正在执行插件动作")
        val detached = context.copy(globalVariables = context.globalVariables.toMap(), nodeOutputs = context.nodeOutputs.mapValues { (_, output) ->
            output.copy(artifacts = output.artifacts.toList(), variables = output.variables.toMap(),
                structuredResult = output.structuredResult?.let { Json.parseToJsonElement(it.toString()) as JsonObject })
        }, upstreamNodeIds = context.upstreamNodeIds?.toList())
        val result = try { extension.execute(PluginWorkflowRequest(arguments, detached)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); return failed("插件动作异常，副作用状态请人工核验") }
        currentCoroutineContext().ensureActive()
        val text = redactor.redact(result.text).take(64_000)
        return NodeExecutionOutput(if (result.success) NodeRunStatus.SUCCESS else NodeRunStatus.FAILED,
            exitCode = if (result.success) 0 else 1, textOutput = text, error = if (result.success) null else "插件动作失败")
    }
    private fun failed(message: String) = NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 1, error = message)
}
