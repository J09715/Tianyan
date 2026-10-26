package top.tianyan.app.harness.directory

import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.model.McpToolAnnotations
import top.tianyan.app.harness.mcp.McpManager
import top.tianyan.app.harness.mcp.McpToolCallPolicy
import top.tianyan.app.harness.JsonArgs

/**
 * use_capability 统一代理的分发器（自 ToolExecutor 零增长迁出，对齐 Reasonix 的代理入口）：
 * - list：列出已启用的 MCP 服务与内置宿主能力域，**不启动任何服务器进程**；
 * - inspect：server="host" 返回目录清单（零进程、零缓存依赖）；其余服务查看工具清单与参数
 *   （缓存为空时按需发现一次——模型必须拿到完整清单才能构造 call）；
 * - call：server="host" 走 [HostCapabilityDirectory] 展平后进入与直接 host 调用完全相同的
 *   执行路径（审批已在 ToolExecutor 入口按同一矩阵完成）；其余按 (server, tool) 交给
 *   MCP 管理器执行（未连接的服务在此按需启动并发现）；
 * - decline：模型显式放弃某能力，确认即回。
 *
 * 宿主域调用不依赖 MCP 管理器：目录是纯静态事实源，inspect/call 全部本地完成。
 */
class CapabilityToolRouter(
    private val mcpManager: McpManager?,
    /** 嵌套记录脱敏（调用方注入 SecretRedactor；目录层保持纯净）。 */
    private val argRedactor: (String) -> String = { it },
    private val plugins: top.tianyan.app.harness.plugins.HarnessPluginRegistry? = null,
    /** 按工具策略过滤模型可见面并在执行前拒绝已禁用工具。 */
    private val toolPolicy: McpToolCallPolicy? = null,
    /** Bot 级能力白名单：设置后非白名单 MCP 服务在 list/inspect/call 一律拒绝。 */
    private val capabilityScope: top.tianyan.app.harness.bot.BotCapabilityScope? = null,
    /** 宿主执行通道：与直接 host 调用共用同一 executeHost（含输出上限与截图 metadata 附带）。 */
    private val hostExecutor: suspend (JsonObject, String?, String, MutableMap<String, String>) -> Pair<Boolean, String>,
) {
    suspend fun execute(
        args: JsonObject,
        workspace: String,
        parentToolCallId: String?,
        operationId: String?,
        sessionId: String,
        metadata: MutableMap<String, String>,
        scriptCall: (suspend (String, String, JsonObject) -> Pair<Boolean, String>)? = null,
    ): Pair<Boolean, String> {
        val action = args.stringArg("action")?.lowercase().orEmpty()
        return when (action) {
            "list" -> listCapabilities(sessionId)
            "inspect" -> inspect(args, sessionId)
            "call" -> call(args, workspace, parentToolCallId, operationId, sessionId, metadata)
            "decline" -> {
                val serverId = args.stringArg("server").orEmpty().trim()
                val tool = args.stringArg("tool").orEmpty().trim()
                true to "已记录：不再尝试 ${if (serverId.isNotBlank()) "$serverId." else ""}$tool。请改用其他方式完成任务或向用户说明障碍。"
            }
            "script" -> script(args, scriptCall)
            else -> false to "action 必须是 list / inspect / call / decline / script 之一"
        }
    }

    /**
     * codemode 脚本动作：模型写 JS，脚本内 capability.call(server, tool, args) 批量/循环/条件
     * 调用能力域。每条内层调用经调用方注入的受控入口走完整校验、审批、
     * 嵌套留痕与脱敏——脚本是编排层，不是绕过审批的通道。
     */
    private suspend fun script(
        args: JsonObject,
        scriptCall: (suspend (String, String, JsonObject) -> Pair<Boolean, String>)?,
    ): Pair<Boolean, String> {
        val dispatch = scriptCall ?: return false to "脚本受控执行入口未初始化，本次调用未执行。"
        val code = args.stringArg("code").orEmpty()
        if (code.isBlank()) return false to "script 需要 code 参数（JS 脚本；全局对象 capability.call(server, tool, args) 返回 {ok, output}）"
        val timeoutMs = args.stringArg("timeout_seconds")?.trim()?.toLongOrNull()
            ?.coerceIn(1, CapabilityScriptRunner.MAX_TIMEOUT_SECONDS)?.times(1000)
            ?: CapabilityScriptRunner.DEFAULT_TIMEOUT_MS
        val runner = CapabilityScriptRunner(argRedactor, dispatch)
        return runner.execute(code, timeoutMs)
    }

    private suspend fun listCapabilities(sessionId: String): Pair<Boolean, String> {
        // Bot 能力白名单：只列出该 Bot 可用的 MCP 服务，让模型一开始就看到正确的可用面。
        val allowed = capabilityScope?.allowedMcpServers(sessionId)
        val summaries = mcpManager?.enabledServerSummaries().orEmpty()
            .filter { allowed == null || it.id.lowercase() in allowed || it.name.lowercase() in allowed }
        val hostLine = HostCapabilityDirectory.listSummaryLine()
        val pluginLines = plugins?.summaries().orEmpty()
        if (summaries.isEmpty()) {
            return true to buildString {
                appendLine("可用的能力域：")
                appendLine(hostLine)
                if (pluginLines.isNotEmpty()) appendLine(pluginLines)
                append("未启用任何 MCP 服务。可在「设置 → MCP 插件与协议生态」启用内置能力或添加自定义服务。")
            }
        }
        return true to buildString {
            appendLine("可用的能力域（MCP 服务未连接的在首次 call 时自动启动并发现工具）：")
            appendLine(hostLine)
            if (pluginLines.isNotEmpty()) appendLine(pluginLines)
            summaries.forEach { summary ->
                appendLine(
                    "- ${summary.id} · ${summary.name} · 已缓存 ${summary.cachedToolCount} 个工具 · " +
                        when (summary.processState) {
                            top.tianyan.app.core.model.McpConnectionState.SLEEPING -> "已就绪（休眠）"
                            top.tianyan.app.core.model.McpConnectionState.WAKING -> "正在唤醒"
                            top.tianyan.app.core.model.McpConnectionState.OFFLINE -> "进程离线"
                            else -> if (summary.connected) "工具目录已就绪" else "未发现工具"
                        },
                )
            }
            append("用 inspect 查看某能力域的工具清单与参数，用 call 调用。")
        }
    }

    private suspend fun inspect(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val serverId = args.stringArg("server")?.trim().orEmpty()
        if (serverId.isBlank()) return false to "inspect 需要 server 参数（先用 list 查看可用的能力域 id）"
        if (serverId.startsWith("plugin.")) return plugins?.inspect(serverId) ?: (false to "插件目录未初始化")
        if (serverId.lowercase() == HostCapabilityDirectory.SERVER_ID) {
            return true to HostCapabilityDirectory.inspectListing()
        }
        capabilityDenied(sessionId, serverId)?.let { return false to it }
        val manager = mcpManager ?: return false to "未初始化 MCP 管理器"
        // 缓存为空 = 服务尚未连接过：按需发现一次（唯一会拉起进程的 inspect 场景——
        // 模型无从得知未连接服务的工具名，必须给它完整清单才能构造 call）
        var tools = manager.cachedToolsOf(serverId)
        if (tools.isEmpty()) {
            tools = manager.discoverServerTools(serverId)
        }
        if (tools.isEmpty()) {
            val lastError = manager.lastErrorForCall(serverId)
            return false to "MCP[$serverId] 工具发现失败或服务不可用${lastError?.let { "：$it" } ?: "（未启用或不存在）"}。" +
                "可稍后重试 inspect，或检查该服务的设置与沙箱环境。"
        }
        val visibleTools = toolPolicy?.filterEnabled(serverId, tools) ?: tools
        if (visibleTools.isEmpty()) {
            return false to "MCP[$serverId] 的工具都已在设置中禁用；模型侧不提供任何可调用工具。" +
                "如需使用，请用户在 MCP 服务详情中重新启用。"
        }
        val rendered = visibleTools.joinToString("\n\n") { tool ->
            buildString {
                appendLine("### ${tool.name}")
                if (tool.description.isNotBlank()) appendLine(tool.description.trim().take(400))
                if (tool.parametersJson.isNotBlank() && tool.parametersJson != "{}") {
                    appendLine("参数：${tool.parametersJson.take(1200)}")
                }
            }
        }
        val body = if (rendered.length > MAX_INSPECT_CHARS) {
            rendered.take(MAX_INSPECT_CHARS) + "\n…[清单过长已截断，可直接按已知工具名 call]"
        } else {
            rendered
        }
        return true to "MCP[$serverId] 工具清单（${visibleTools.size} 个）：\n$body"
    }

    /**
     * call：内层工具经 [NestedCalls] 留下有界审计记录（参数/错误脱敏后入库 metadata，
     * 结果正文不重复存储）。被目录拒绝的尝试记为 blocked——"试图做什么"本身就是审计事实。
     */
    private suspend fun call(
        args: JsonObject,
        workspace: String,
        parentToolCallId: String?,
        operationId: String?,
        sessionId: String,
        metadata: MutableMap<String, String>,
    ): Pair<Boolean, String> {
        val serverId = args.stringArg("server")?.trim().orEmpty()
        val tool = args.stringArg("tool")?.trim().orEmpty()
        if (serverId.isBlank() || tool.isBlank()) {
            return false to "call 需要 server 与 tool 参数（先 inspect 查看可用的工具名与参数）"
        }
        val callArgs = args["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return invokeCapability(serverId, tool, callArgs, workspace, parentToolCallId, operationId, sessionId, metadata)
    }

    /**
     * 内层能力调用核心：host deferred 校验 / MCP 分派 + 有界嵌套留痕。
     * 直接 call 与 codemode 脚本共用——审批与留痕语义不因编排形态改变。
     */
    internal suspend fun invokeCapability(
        serverId: String,
        tool: String,
        callArgs: JsonObject,
        workspace: String,
        parentToolCallId: String?,
        operationId: String?,
        sessionId: String,
        metadata: MutableMap<String, String>,
    ): Pair<Boolean, String> {
        val logicalName = "${serverId.lowercase()}.$tool"
        val startedAt = System.currentTimeMillis()

        val outcome: Pair<Boolean, String> = if (serverId.lowercase() == HostCapabilityDirectory.SERVER_ID) {
            val deferred = HostCapabilityDirectory.deferredAction(tool)
            if (deferred == null || HostCapabilityDirectory.isHidden(tool)) {
                val reason = if (HostCapabilityDirectory.isHidden(tool)) {
                    "工具「$tool」已在当前构建中停用。"
                } else {
                    "宿主能力域没有工具「$tool」。用 inspect 查看 host 域的可用清单；" +
                        "高频查询类动作（status/logcat/screen_click 等）请直接调用 host 工具。"
                }
                NestedCalls.append(
                    metadata, parentToolCallId, logicalName, NestedCalls.STATUS_BLOCKED,
                    System.currentTimeMillis() - startedAt, callArgs.toString(), reason, argRedactor,
                )
                return false to reason
            }
            hostExecutor(HostCapabilityDirectory.flattenHostArgs(tool, callArgs), operationId, sessionId, metadata)
        } else if (serverId.startsWith("plugin.")) {
            plugins?.call(serverId, tool, top.tianyan.app.harness.plugins.PluginToolRequest(callArgs, sessionId, workspace, operationId))
                ?: (false to "插件目录未初始化")
        } else {
            val manager = mcpManager ?: run {
                val reason = "未初始化 MCP 管理器"
                NestedCalls.append(
                    metadata, parentToolCallId, logicalName, NestedCalls.STATUS_BLOCKED,
                    System.currentTimeMillis() - startedAt, callArgs.toString(), reason, argRedactor,
                )
                return false to reason
            }
            capabilityDenied(sessionId, serverId)?.let { reason ->
                NestedCalls.append(
                    metadata, parentToolCallId, logicalName, NestedCalls.STATUS_BLOCKED,
                    System.currentTimeMillis() - startedAt, callArgs.toString(), reason, argRedactor,
                )
                return false to reason
            }
            if (toolPolicy?.policyFor(serverId, tool)?.enabled == false) {
                val reason = "MCP[$serverId] 工具「$tool」已在设置中禁用，本次调用未执行。请改用其他能力或让用户重新启用。"
                NestedCalls.append(
                    metadata, parentToolCallId, logicalName, NestedCalls.STATUS_BLOCKED,
                    System.currentTimeMillis() - startedAt, callArgs.toString(), reason, argRedactor,
                )
                return false to reason
            }
            manager.executeCapabilityTool(serverId, tool, callArgs, workspace)
        }

        NestedCalls.append(
            metadata = metadata,
            parentToolCallId = parentToolCallId,
            name = logicalName,
            status = if (outcome.first) NestedCalls.STATUS_OK else NestedCalls.STATUS_ERROR,
            durationMs = System.currentTimeMillis() - startedAt,
            arguments = callArgs.toString(),
            error = if (outcome.first) null else outcome.second,
            redact = argRedactor,
        )
        return outcome
    }

    /** Bot 能力白名单判定：非 null 返回值即拒绝原因。宿主与插件域不受白名单约束。 */
    private suspend fun capabilityDenied(sessionId: String, serverId: String): String? {
        val allowed = capabilityScope?.allowedMcpServers(sessionId) ?: return null
        if (serverId.lowercase() in allowed) return null
        return "MCP[$serverId] 不在当前 Bot 的能力白名单内，本次调用未执行。" +
            "请先用 list 查看可用能力域；如需该服务，请用户在 Bot 详情的能力库中添加。"
    }
}

/** 本文件内共享的字符串参数读取：复用 [JsonArgs]，去首尾空白，空串视作缺省。 */
private fun JsonObject.stringArg(key: String): String? =
    JsonArgs.optionalString(this, key)?.trim()?.takeIf { it.isNotEmpty() }

/** inspect 清单输出上限：超出截断并指引直接 call。 */
private const val MAX_INSPECT_CHARS = 16_000

/**
 * 从 MCP 缓存解析审批注解：use_capability(call) 按 (server, tool) 查缓存；
 * legacy mcp__<server>__<tool> 直接名按名段解析。未缓存（服务尚未连接）或解析
 * 失败一律返回 null——保守不升级，绝不臆造注解。
 */
suspend fun McpManager?.annotationsForCall(args: JsonObject, rawToolName: String?): McpToolAnnotations? {
    val manager = this ?: return null
    val server: String
    val tool: String
    if (rawToolName == "use_capability") {
        if (args.stringArg("action")?.lowercase() != "call") return null
        server = args.stringArg("server")?.trim().orEmpty()
        tool = args.stringArg("tool")?.trim().orEmpty()
    } else {
        // legacy 兼容路径：mcp__<server 段>__<tool>（编码名含 hash 尾段，取中段足够）
        val parts = rawToolName?.split("__").orEmpty()
        if (parts.size < 3 || parts[0] != "mcp") return null
        server = parts[1]
        tool = parts[2]
    }
    if (server.isBlank() || tool.isBlank()) return null
    return manager.cachedToolsOf(server).firstOrNull { it.name == tool }?.annotations
}
