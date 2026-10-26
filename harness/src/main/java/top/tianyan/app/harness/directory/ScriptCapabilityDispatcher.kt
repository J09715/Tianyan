package top.tianyan.app.harness.directory

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.mcp.McpManager
import top.tianyan.app.harness.mcp.McpToolApiName
import top.tianyan.app.harness.validation.ToolSchemaValidator

/** Abort JS control flow without losing approval handoff flags. */
internal class ScriptCallInterrupted(val result: ToolResult) : RuntimeException()

/** A script is orchestration only. Each call re-enters ToolExecutor's policy/checkpoint boundary. */
internal class ScriptCapabilityDispatcher(
    private val parentToolCallId: String,
    private val metadata: MutableMap<String, String>,
    private val mcpManager: McpManager?,
    private val redact: (String) -> String,
    private val plugins: top.tianyan.app.harness.plugins.HarnessPluginRegistry? = null,
    private val execute: suspend (ToolCall) -> ToolResult,
) {
    suspend fun call(server: String, tool: String, args: JsonObject): Pair<Boolean, String> {
        currentCoroutineContext().ensureActive()
        val started = System.nanoTime()
        val problems = validate(server, tool, args)
        currentCoroutineContext().ensureActive()
        if (problems.isNotEmpty()) {
            val output = "能力参数校验失败：${problems.joinToString("；")}"
            record(server, tool, args, NestedCalls.STATUS_BLOCKED, started, output)
            return false to output
        }
        // The pending request binds to the parent transcript call, but contains ONLY
        // this call's parameters. Approval replay never reruns earlier script effects.
        val call = ToolCall(parentToolCallId, System.currentTimeMillis(), HarnessTool.MCP,
            buildJsonObject {
                put("action", "call"); put("server", server); put("tool", tool); put("arguments", args)
                put(SCRIPT_CALL_KEY, true) // Approval replay provenance, never sent to the underlying tool.
            }, rawToolName = "use_capability")
        val result = execute(call)
        metadata.putAll(result.metadata.filterKeys { it != NestedCalls.METADATA_KEY })
        result.imageDataUrl?.let { metadata["image_payload"] = it }
        val interrupted = result.awaitingApproval || result.approvalDeferred
        record(server, tool, args, if (interrupted) NestedCalls.STATUS_BLOCKED
            else if (result.success) NestedCalls.STATUS_OK else NestedCalls.STATUS_ERROR,
            started, if (result.success) null else result.output)
        if (interrupted) {
            val image = metadata.remove("image_payload")
            throw ScriptCallInterrupted(result.copy(metadata = metadata.toMap(), imageDataUrl = image ?: result.imageDataUrl, output =
                "脚本已停止编排；此前完成的调用不会重放。批准后仅执行当前调用，请根据结果继续剩余任务。\n" + result.output))
        }
        currentCoroutineContext().ensureActive()
        return result.success to result.output
    }

    private suspend fun validate(server: String, tool: String, args: JsonObject): List<String> {
        if (server.isBlank() || tool.isBlank()) return listOf("server 与 tool 不能为空")
        if (server.startsWith("plugin.")) return plugins?.problems(server, tool, args) ?: listOf("插件目录未初始化")
        if (server.lowercase() == HostCapabilityDirectory.SERVER_ID) {
            if (HostCapabilityDirectory.deferredAction(tool) == null || HostCapabilityDirectory.isHidden(tool)) {
                return listOf("宿主能力域没有可调用工具「$tool」，请 inspect 后按目录调用")
            }
            return ToolSchemaValidator.validate(HostCapabilityDirectory.schemaFor(tool), args)
        }
        val manager = mcpManager ?: return listOf("未初始化 MCP 管理器")
        val tools = manager.cachedToolsOf(server).ifEmpty { manager.discoverServerTools(server) }
        val info = tools.firstOrNull { it.name == tool } ?: return listOf("MCP[$server] 没有可调用工具「$tool」")
        // Discover before policy evaluation so destructive/open-world annotations are available.
        return ToolSchemaValidator.problemsFor(McpToolApiName.encode(info), args, tools)
    }

    private fun record(server: String, tool: String, args: JsonObject, status: String, started: Long, error: String?) =
        NestedCalls.append(metadata, parentToolCallId, "${server.lowercase()}.$tool", status,
            (System.nanoTime() - started) / 1_000_000, args.toString(), error, redact)

    companion object {
        private const val SCRIPT_CALL_KEY = "script_call"

        fun replayResult(call: ToolCall, result: ToolResult): ToolResult =
            if (call.rawToolName == "use_capability" && call.args[SCRIPT_CALL_KEY]?.toString() == "true") {
                result.copy(output = "本结果仅对应脚本暂停时待审批的一条调用；脚本其余部分未继续执行。请根据结果继续剩余任务，勿重放此前完成的调用。\n" + result.output)
            } else result
    }
}
