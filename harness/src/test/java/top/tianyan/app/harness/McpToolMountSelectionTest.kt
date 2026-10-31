package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.core.datastore.McpToolPolicyMap
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.core.model.McpToolPolicy

class McpToolMountSelectionTest {
    private fun server(id: String) = McpServerConfig(id = id, name = "name-$id")
    private fun tool(serverId: String, name: String) =
        McpToolInfo(serverId = serverId, serverName = "name-$serverId", name = name, description = "")

    @Test
    fun `disabled tools are filtered before mount`() {
        val policies: McpToolPolicyMap = mapOf("s1" to mapOf("b" to McpToolPolicy(enabled = false)))
        val mounted = selectMountedTools(
            servers = listOf(server("s1")),
            policies = policies,
            toolsByServer = mapOf("s1" to listOf(tool("s1", "a"), tool("s1", "b"))),
        )
        assertEquals(listOf("a"), mounted.map { it.name })
    }

    @Test
    fun `servers and tools mount in stable order`() {
        val mounted = selectMountedTools(
            servers = listOf(server("s2"), server("s1")),
            policies = emptyMap(),
            toolsByServer = mapOf(
                "s1" to listOf(tool("s1", "z"), tool("s1", "a")),
                "s2" to listOf(tool("s2", "m")),
            ),
        )
        assertEquals(listOf("s1/a", "s1/z", "s2/m"), mounted.map { "${it.serverId}/${it.name}" })
    }

    @Test
    fun `mount caps servers per-server tools and total tools`() {
        val servers = (1..5).map { server("s$it") }
        val tools = servers.associate { server ->
            server.id to (1..30).map { index -> tool(server.id, "t%02d".format(index)) }
        }
        val mounted = selectMountedTools(servers, emptyMap(), tools)
        assertEquals(48, mounted.size)
        assertEquals(setOf("s1", "s2"), mounted.map { it.serverId }.toSet())
        assertEquals(24, mounted.count { it.serverId == "s1" })
        assertEquals(24, mounted.count { it.serverId == "s2" })
    }
}
