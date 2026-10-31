package top.tianyan.app.ui.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall

/**
 * 工具调用的可读名称：MCP 代理调用显示 server · tool，legacy 编码名去掉哈希尾段，
 * 避免把 `mcp__server__tool__abc123` 直接暴露给用户。
 */
internal fun toolDisplayName(call: ToolCall): String {
    if (call.tool != HarnessTool.MCP) return toolName(call.tool, call.rawToolName)
    val raw = call.rawToolName?.takeIf { it.isNotBlank() } ?: return "mcp"
    if (raw == "use_capability") {
        val action = (call.args["action"] as? JsonPrimitive)?.contentOrNull
        if (action == "call") {
            val server = (call.args["server"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val tool = (call.args["tool"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (server != null && tool != null) return "$server · $tool"
        }
        return when (action) {
            "list" -> "能力目录"
            "inspect" -> "能力清单"
            "script" -> "能力脚本"
            else -> "use_capability"
        }
    }
    val parts = raw.split("__")
    return if (parts.size >= 4 && parts[0] == "mcp" && parts[1].isNotBlank() && parts[2].isNotBlank()) {
        "${parts[1]} · ${parts[2]}"
    } else {
        raw
    }
}

internal fun toolName(tool: HarnessTool, rawToolName: String? = null): String =
    rawToolName?.takeIf { it.isNotBlank() } ?: when (tool) {
        HarnessTool.READ -> "read"
        HarnessTool.WRITE -> "write"
        HarnessTool.EDIT -> "edit"
        HarnessTool.BASE -> "base"
        HarnessTool.PROCESS -> "process"
        HarnessTool.HOST -> "host"
        HarnessTool.DOWNLOAD -> "download"
        HarnessTool.MEMORY -> "memory"
        HarnessTool.PLAN -> "plan"
        HarnessTool.SCRATCHPAD -> "scratchpad"
        HarnessTool.HISTORY_SEARCH -> "history.search"
        HarnessTool.HISTORY_READ -> "history.read"
        HarnessTool.BUILD_SCRIPT -> "build_script"
        HarnessTool.SUBAGENT -> "invoke_subagent"
        HarnessTool.MCP -> "mcp"
        HarnessTool.LOAD_RULE -> "load_rule"
        HarnessTool.LOAD_SKILL -> "load_skill"
        HarnessTool.COMPRESS -> "compress"
        HarnessTool.ASK_USER -> "ask_user"
        HarnessTool.RENDER_SURFACE -> "render_surface"
        HarnessTool.RED_TEAM -> "red_team"
    }
