package top.tianyan.app.harness.mcp.server

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.tianyan.app.harness.redteam.RedTeamCoordinator
import top.tianyan.app.harness.redteam.RedTeamToolSchema

/**
 * 把天衍红队工作区能力通过 MCP 暴露给引擎型智能体。
 *
 * ## 为什么需要这一层
 *
 * 红队（`redteam`）原本只是**天衍本体**的一个 LLM 工具：它出现在本体发给 provider 的
 * tool schema 里，由 [RedTeamCoordinator] 在宿主进程内执行。会话一旦绑定引擎
 * （Claude Code / Codex / OpenCode…），执行体就是引擎进程，它看不到天衍的工具表——
 * 于是「换个智能体就没法做红队演练」。
 *
 * ## 桥接方式
 *
 * 天衍本来就跑着一个 MCP server（[McpServerRuntime]，stdio 之外的 HTTP 通道）。
 * 我们把红队作为一个 MCP 工具注册进去，并在 [sessionId] 建立会话时把这份 server
 * 通过 ACP `session/new` 的 `mcpServers` 交给引擎。引擎于是能像调自己的工具一样
 * 调用天衍的红队，事实仍然只读写宿主数据库——引擎只是操作入口，不是数据owner。
 *
 * ## 会话归属
 *
 * MCP 调用本身没有天衍会话上下文，因此工具参数里显式带 `session_id`：
 * [McpServerRuntime] 注入给引擎时会把当轮会话 id 写进配置，引擎照抄即可。
 * 拿不到 session_id 的调用一律拒绝，避免把资产写进错误的会话工作区。
 */
@Singleton
class RedTeamMcpBridge @Inject constructor(
    private val redTeam: RedTeamCoordinator?,
) {
    /** MCP 工具名。带前缀避免与浏览器工具（`browser_*`）撞名。 */
    val toolName: String = TOOL_NAME

    /** 是否可用：协调器未注入（单测精简构造）时不暴露工具。 */
    val available: Boolean get() = redTeam != null

    /** 暴露给引擎的工具声明。 */
    fun describe(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(toolName))
        put("risk", JsonPrimitive("high"))
        put("capability", JsonPrimitive("redteam"))
        put(
            "description",
            JsonPrimitive(
                "会话级红队工作区（资产 / 图谱 / 漏洞 / PoC / 报告）。" +
                    "参数 action 见 inputSchema.enum；session_id 必须是当前天衍会话 id" +
                    "（宿主已在 MCP 配置里给出，直接原样回传）。" +
                    "建议先 action=preflight 与 action=session_info 确认目标已绑定，" +
                    "再用 asset_add / vuln_add 写入，asset_graph / attack_path / report 出结论。",
            ),
        )
        put("inputSchema", inputSchema())
    }

    /** 在既有 schema 上补一个 session_id 必填项。 */
    private fun inputSchema(): JsonObject {
        val base = RedTeamToolSchema.parameters()
        val properties = (base["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val required = (base["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.toMutableList()
            ?: mutableListOf()
        if ("session_id" !in required) required += "session_id"
        return buildJsonObject {
            (base["type"] as? JsonPrimitive)?.let { put("type", it) }
            put(
                "properties",
                buildJsonObject {
                    properties.forEach { (k, v) -> put(k, v) }
                    put(
                        "session_id",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("天衍会话 id；只读写该会话的红队工作区"))
                        },
                    )
                },
            )
            put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    /**
     * 执行一次红队调用。
     *
     * @return MCP 标准响应（content + isError）。
     */
    suspend fun invoke(args: JsonObject): JsonObject {
        // 先校验会话归属再校验能力可用性：归属错了是数据污染，引擎必须立刻知道并停下；
        // 若先报「能力未初始化」，引擎会当成暂时不可用而反复重试，把问题拖成静默失败。
        val sessionId = (args["session_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return errorResult("缺少 session_id：红队事实只属于具体会话，拒绝无归属写入")
        val action = (args["action"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (action !in RedTeamToolSchema.actionNames()) {
            return errorResult(
                "未知 action：${action.ifBlank { "(空)" }}。" +
                    "可用值：${RedTeamToolSchema.actionNames().joinToString(", ")}",
            )
        }
        val coordinator = redTeam ?: return errorResult("红队能力未初始化")
        val outcome = runCatching { coordinator.execute(args, sessionId) }
            .getOrElse { return errorResult("红队执行失败：${it.message ?: it::class.simpleName}") }
        val (success, message) = outcome
        return buildJsonObject {
            put(
                "content",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(message.take(MAX_TEXT_CHARS)))
                        },
                    ),
                ),
            )
            put("isError", JsonPrimitive(!success))
        }
    }

    private fun errorResult(message: String): JsonObject = buildJsonObject {
        put(
            "content",
            kotlinx.serialization.json.JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(message))
                    },
                ),
            ),
        )
        put("isError", JsonPrimitive(true))
    }

    companion object {
        const val TOOL_NAME = "tianyan_redteam"

        /** 单次回给引擎的文本上限：红队报告可能很长，截断防止撑爆引擎上下文。 */
        const val MAX_TEXT_CHARS = 20_000
    }
}
