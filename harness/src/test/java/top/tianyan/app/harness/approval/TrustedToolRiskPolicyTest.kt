package top.tianyan.app.harness.approval

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.directory.HostCapabilityDirectory
import top.tianyan.app.harness.mcp.McpToolApiName

class TrustedToolRiskPolicyTest {
    private val engine = ApprovalPolicyEngine(HarnessPathResolver())
    @Test fun `supported host actions share classifications across routes and modes`() {
        val supported = HostCapabilityDirectory.DIRECT_ACTIONS.toSet() + HostCapabilityDirectory.DEFERRED_ACTIONS.map { it.name }
        assertEquals(supported, TrustedToolRiskPolicy.hostRules.keys)
        supported.forEach { action ->
            val args = buildJsonObject { put("action", action) }
            val proxy = buildJsonObject { put("action", "call"); put("server", "host"); put("tool", action); put("arguments", JsonObject(emptyMap())) }
            ApprovalMode.entries.forEach { mode ->
                assertEquals("$action $mode", engine.decide(mode, HarnessTool.HOST, args, "/workspace"),
                    engine.decide(mode, HarnessTool.MCP, proxy, "/workspace", "use_capability"))
            }
            assertEquals(engine.planBlock(HarnessTool.HOST, args), engine.planBlock(HarnessTool.MCP, proxy, "use_capability"))
        }
    }

    @Test fun `FULL_ACCESS bypasses critical approvals while PLAN remains read only`() {
        listOf("exec", "package_uninstall_user").forEach { action ->
            val args = buildJsonObject { put("action", action) }
            assertFalse(engine.decide(ApprovalMode.FULL_ACCESS, HarnessTool.HOST, args, "").required)
            assertTrue(engine.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args, "").required)
            assertEquals("critical", engine.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args, "").riskLevel)
            assertNotNull(engine.planBlock(HarnessTool.HOST, args))
        }
    }

    @Test fun `browser rules cannot be borrowed by external servers or readOnly annotations`() {
        TrustedToolRiskPolicy.browserRules.forEach { (name, rule) ->
            val tool = McpToolInfo(BuiltinMcpPresets.BROWSER_BUILTIN_ID, "browser", name, "")
            val encoded = McpToolApiName.encode(tool)
            val proxy = buildJsonObject { put("action", "call"); put("server", tool.serverId); put("tool", name) }
            ApprovalMode.entries.forEach { mode ->
                val direct = engine.decide(mode, HarnessTool.MCP, JsonObject(emptyMap()), "", encoded)
                val deferred = engine.decide(mode, HarnessTool.MCP, proxy, "", "use_capability")
                assertEquals("$name $mode", direct.required, deferred.required)
                assertEquals(direct.riskLevel, deferred.riskLevel)
                assertEquals(mode != ApprovalMode.FULL_ACCESS && !rule.readOnly, direct.required)
            }
        }
        assertTrue(engine.decide(ApprovalMode.ASSISTED, HarnessTool.MCP, JsonObject(emptyMap()), "",
            "mcp__untrusted__browser_snapshot", McpToolAnnotations(readOnlyHint = true)).required)
        assertNotNull(engine.planBlock(HarnessTool.MCP, JsonObject(emptyMap()), "mcp__untrusted__browser_snapshot"))
        val trusted = McpToolInfo(BuiltinMcpPresets.BROWSER_BUILTIN_ID, "browser", "browser_snapshot", "")
        val collision = trusted.copy(serverId = trusted.serverId + "-external")
        val alias = McpToolApiName.encode(collision)
        assertEquals(McpToolApiName.encode(trusted).substringAfter("__").substringBefore("__"), alias.substringAfter("__").substringBefore("__"))
        assertTrue(engine.decide(ApprovalMode.ASSISTED, HarnessTool.MCP, JsonObject(emptyMap()), "", alias).required)
        assertNotNull(engine.planBlock(HarnessTool.MCP, JsonObject(emptyMap()), alias))
    }
}
