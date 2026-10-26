package top.tianyan.app.harness.mcp

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.McpToolInfo

class McpToolDefinitionsTest {
    @Test
    fun `mounted tool uses stable encoded name and parsed schema`() {
        val info = McpToolInfo(
            serverId = "srv",
            serverName = "Server",
            name = "read_file",
            description = "Read a file",
            parametersJson = """{"type":"object","properties":{"path":{"type":"string"}}}""",
        )
        val definition = info.toApiToolDefinition()
        assertTrue(definition.function.name.startsWith("mcp__srv__read_file__"))
        assertTrue(definition.function.description.startsWith("[Server]"))
        assertEquals("object", (definition.function.parameters["type"] as JsonPrimitive).content)
    }

    @Test
    fun `blank schema still declares an object`() {
        val info = McpToolInfo(serverId = "srv", serverName = "Server", name = "noop", description = "")
        val definition = info.toApiToolDefinition()
        assertEquals("object", (definition.function.parameters["type"] as JsonPrimitive).content)
    }
}
