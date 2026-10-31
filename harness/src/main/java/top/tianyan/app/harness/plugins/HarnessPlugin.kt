package top.tianyan.app.harness.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import top.tianyan.app.harness.LlmApiAdapter
import top.tianyan.app.harness.core.LlmApi
import top.tianyan.app.harness.core.ToolCheckpoint
import top.tianyan.app.harness.ToolExecutionRequest
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.workflow.NodeExecutor
import top.tianyan.app.plugin.api.PluginDescriptor

/** Trusted compiled contributions. Package JSON cannot construct callbacks or access these APIs. */
data class HarnessPlugin(
    val descriptor: PluginDescriptor,
    val tools: List<PluginTool> = emptyList(),
    val contexts: List<PluginContext> = emptyList(),
    val providers: List<PluginProvider> = emptyList(),
    val workflows: List<PluginWorkflow> = emptyList(),
    val nodes: List<PluginWorkflowNode> = emptyList(),
    val checkpoints: List<ToolCheckpoint<ToolExecutionRequest, ToolResult>> = emptyList(),
    /** Stable package identity for workflow lookup; tool domains themselves are version-pinned. */
    val packageId: String? = null,
)

data class PluginToolRequest(val arguments: JsonObject, val sessionId: String, val workspace: String, val operationId: String?)
data class PluginToolOutput(val success: Boolean, val text: String)
data class PluginTool(val id: String, val description: String, val schema: JsonObject, val execute: suspend (PluginToolRequest) -> PluginToolOutput)
data class PluginContextRequest(val sessionId: String, val workspace: String)
data class PluginContext(val id: String, val provide: suspend (PluginContextRequest) -> String)
/** Receives the selected request's model credentials through LlmApiAdapter; no credential repository. */
data class PluginProvider(val id: String, val api: LlmApi, val providerId: String? = null, val create: (OkHttpClient, Json) -> LlmApiAdapter)
data class PluginWorkflow(val id: String, val create: () -> NodeExecutor)
data class PluginWorkflowRequest(val arguments: JsonObject, val context: top.tianyan.app.core.model.workflow.WorkflowRuntimeContext)
data class PluginWorkflowNode(val id: String, val description: String, val schema: JsonObject,
    val execute: suspend (PluginWorkflowRequest) -> PluginToolOutput)
