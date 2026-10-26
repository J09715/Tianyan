package top.tianyan.app.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall

class McpToolDisplayNameTest {
    private fun call(raw: String?, args: JsonObject = JsonObject(emptyMap())) =
        ToolCall("id", 0L, HarnessTool.MCP, args, rawToolName = raw)

    @Test
    fun `legacy encoded name drops hash suffix`() {
        assertEquals("browser · screenshot", toolDisplayName(call("mcp__browser__screenshot__abcdef123456")))
    }

    @Test
    fun `proxy call resolves server and tool`() {
        val args = buildJsonObject {
            put("action", "call")
            put("server", "mcp_git")
            put("tool", "git_log")
        }
        assertEquals("mcp_git · git_log", toolDisplayName(call("use_capability", args)))
    }

    @Test
    fun `proxy metadata actions use readable labels`() {
        assertEquals(
            "能力清单",
            toolDisplayName(call("use_capability", buildJsonObject { put("action", "inspect") })),
        )
        assertEquals(
            "能力脚本",
            toolDisplayName(call("use_capability", buildJsonObject { put("action", "script") })),
        )
    }
}
