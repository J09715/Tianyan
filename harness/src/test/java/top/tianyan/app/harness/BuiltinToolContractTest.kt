package top.tianyan.app.harness

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.harness.directory.HostCapabilityDirectory

class BuiltinToolContractTest {
    private val model = ModelConfig(name = "test", provider = "test", model = "test", baseUrl = "http://localhost", apiKey = null)

    @Test
    fun `load rule accepts every registered rule including image delivery`() {
        val tool = ProviderClient.buildDynamicTools(model).single { it.function.name == "load_rule" }
        val properties = tool.function.parameters["properties"] as kotlinx.serialization.json.JsonObject
        val rule = properties["rule"] as kotlinx.serialization.json.JsonObject
        val names = (rule["enum"] as kotlinx.serialization.json.JsonArray)
            .map { (it as kotlinx.serialization.json.JsonPrimitive).content }.toSet()
        assertEquals(top.tianyan.app.harness.prompt.PromptRouter.RuleBlock.entries.map { it.loadName }.toSet(), names)
    }
    @Test
    fun `build script tool exposes lifecycle and binding actions`() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "build_script" }
        val action = tool.function.parameters["properties"]!!.jsonObject["action"]!!.jsonObject
        val values = action["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("list", "get", "create", "update", "delete", "bind", "unbind"), values)
    }

    @Test
    fun baseExposesBoundedTimeoutOverride() {
        val base = ProviderClient.TOOLS.single { it.function.name == "base" }
        val timeout = base.function.parameters["properties"]
            ?.jsonObject
            ?.get("timeout_seconds")
            ?.jsonObject

        assertNotNull(timeout)
        assertEquals("1", timeout?.get("minimum")?.toString())
        assertEquals("900", timeout?.get("maximum")?.toString())
    }

    @Test
    fun processExposesManagedLifecycleActions() {
        val process = ProviderClient.TOOLS.single { it.function.name == "process" }
        val encoded = process.function.parameters.toString()

        listOf("start", "status", "logs", "list", "stop").forEach { action ->
            assertTrue(encoded.contains("\"$action\""))
        }
        assertTrue(process.function.description.contains("不要使用 nohup"))
    }

    @Test
    fun historyToolsExposeSearchAndReadContracts() {
        val search = ProviderClient.TOOLS.single { it.function.name == "history_search" }
        val read = ProviderClient.TOOLS.single { it.function.name == "history_read" }
        assertTrue(search.function.parameters.toString().contains("query"))
        val parameters = read.function.parameters
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertFalse(parameters.containsKey("anyOf"))
        assertFalse(parameters.containsKey("oneOf"))
        assertTrue(parameters["properties"]!!.jsonObject.containsKey("message_id"))
        assertTrue(parameters["properties"]!!.jsonObject.containsKey("index"))
        assertTrue(read.function.description.contains("至少提供一个"))
        val props = parameters["properties"]!!.jsonObject
        assertEquals("0", props["offset"]!!.jsonObject["minimum"].toString())
        assertEquals(HistoryReadPage.MAX_LIMIT.toString(), props["limit"]!!.jsonObject["maximum"].toString())
        assertTrue(read.function.description.contains("next_offset"))
    }

    @Test
    fun subagentToolUsesDepartmentIndexRoutingWithoutRequiringExactRole() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "invoke_subagent" }
        val subagents = tool.function.parameters["properties"]!!.jsonObject["subagents"]!!.jsonObject
        val item = subagents["items"]!!.jsonObject
        val properties = item["properties"]!!.jsonObject
        val required = item["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        val departments = properties["department"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }

        assertTrue("department" in required)
        assertTrue("agentQuery" in required)
        assertTrue("prompt" in required)
        assertTrue("writePaths" in required)
        assertTrue("writePaths" in properties)
        assertTrue("role" in properties)
        assertTrue("role" !in required)
        assertEquals(9, departments.size)
        assertEquals("6", subagents["maxItems"].toString())
        assertTrue(tool.function.description.contains("本地索引"))
        assertTrue(tool.function.description.contains("同一次调用"))
        assertFalse("schema must not promise background completion injection", properties.containsKey("background"))
        assertFalse("description must not promise background completion injection", tool.function.description.contains("background=true"))
    }

    @Test
    fun dualAgentToolExposesIsolatedModelAndDagControls() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "invoke_dual_agent" }
        val properties = tool.function.parameters["properties"]!!.jsonObject
        val required = tool.function.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("prompt"), required)
        assertTrue("planner_model" in properties)
        assertTrue("executor_model" in properties)
        assertEquals("30", properties["max_steps"]!!.jsonObject["maximum"].toString())
        assertTrue(tool.function.description.contains("DAG"))
    }

    @Test
    fun hostDirectSurfaceKeepsHighFrequencyActionsAndPointsToDirectory() {
        val host = ProviderClient.TOOLS.single { it.function.name == "host" }
        val enum = host.function.parameters["properties"]!!.jsonObject["action"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }

        // direct 声明与目录的 DIRECT_ACTIONS 完全一致（唯一事实源）
        assertEquals(HostCapabilityDirectory.DIRECT_ACTIONS, enum)
        // deferred 动作绝不出现在 direct 声明中（prompt 负担迁移的合同）
        HostCapabilityDirectory.DEFERRED_ACTIONS.forEach { action ->
            assertFalse("deferred 动作 ${action.name} 不得出现在 host 直接声明中", action.name in enum)
        }
        // 高频核心动作仍在场
        listOf("status", "exec", "settings_get", "logcat", "device_status", "screen_click", "app_launch")
            .forEach { action -> assertTrue("direct host 必须保留 $action", action in enum) }
        assertTrue(host.function.description.contains("Android"))
        // direct 声明必须指引按需发现域（模型要知道 virtual_screen_* 去哪找）
        assertTrue(host.function.description.contains("use_capability"))
        assertTrue(host.function.description.contains("server=\"host\""))
    }

    @Test
    fun allProviderFunctionNamesUsePortableCharacters() {
        val names = ProviderClient.buildDynamicTools(model).map { it.function.name }

        names.forEach { name ->
            assertTrue("invalid provider function name: $name", name.matches(Regex("^[a-zA-Z0-9_-]+$")))
            assertTrue("provider function name is too long: $name", name.length <= 64)
        }
    }

    @Test
    fun `provider surface exposes use_capability proxy and never leaks mcp schemas`() {
        val tools = ProviderClient.buildDynamicTools(model)
        val names = tools.map { it.function.name }

        // 统一代理必须在场：MCP 能力的唯一入口
        assertTrue("use_capability 必须在 provider 工具面中", "use_capability" in names)
        // MCP 工具 schema 绝不进入 provider 可见面（MCP 清单变化不再击穿前缀缓存）
        assertTrue(
            "provider 工具面不得泄漏 mcp__ 工具：${names.filter { it.startsWith("mcp__") }}",
            names.none { it.startsWith("mcp__") },
        )
        // 工具数组必须逐字节稳定（重复构建完全一致）
        assertEquals(tools, ProviderClient.buildDynamicTools(model))
    }

    @Test
    fun `explicitly mounted mcp tools appear with encoded names`() {
        val mounted = McpToolInfo(serverId = "srv", serverName = "Server", name = "read_file", description = "")
        val tools = ProviderClient.buildDynamicTools(model.copy(dynamicMcpTools = listOf(mounted)))
        assertTrue(tools.any { it.function.name.startsWith("mcp__srv__read_file__") })
    }
}
