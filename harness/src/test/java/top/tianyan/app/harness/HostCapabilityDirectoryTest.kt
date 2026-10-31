package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.McpToolAnnotations
import top.tianyan.app.harness.directory.CapabilityToolRouter
import top.tianyan.app.harness.directory.HostCapabilityDirectory
import top.tianyan.app.harness.directory.NestedCalls
import top.tianyan.app.harness.validation.ToolSchemaValidator

/**
 * 宿主能力目录（direct/deferred 拆分）与 use_capability 宿主域路由的合同测试：
 * - 拆分不变量：DIRECT ∪ DEFERRED 覆盖宿主执行器支持的全部动作且不相交；
 * - 审批等价：deferred 调用经引擎委派后与直接 host 调用判定一致；
 * - PLAN 一致：deferred 动作在只读规划下一律拦截，只读动作全部留在 direct。
 */
class HostCapabilityDirectoryTest {

    private val policy = ApprovalPolicyEngine(HarnessPathResolver())
    private val workspace = "/workspace/project"

    /** 宿主执行器支持的全部动作（与目录拆分共同锁定执行器合同）。 */
    private val executorActions = setOf(
        "status", "exec", "settings_get", "settings_put", "package_list", "package_disable",
        "package_enable", "package_uninstall_user", "app_list", "app_freeze", "app_unfreeze",
        "app_grant_permission", "logcat", "device_status", "screen_observe", "screen_click",
        "screen_double_click", "screen_long_press", "screen_swipe", "screen_scroll",
        "screen_input_text", "paste_text", "screen_key", "app_launch", "screen_capture",
        "virtual_screen_ensure", "virtual_screen_launch", "virtual_screen_screenshot",
        "virtual_screen_click", "virtual_screen_double_click", "virtual_screen_long_press",
        "virtual_screen_swipe", "virtual_screen_scroll", "virtual_screen_key",
        "virtual_screen_input_text", "virtual_screen_set_text", "virtual_screen_wait",
        "virtual_screen_close", "virtual_screen_show", "virtual_screen_hide", "virtual_screen_task",
    )

    private fun capabilityArgs(tool: String, vararg arguments: Pair<String, String>) = buildJsonObject {
        put("action", "call")
        put("server", "host")
        put("tool", tool)
        put(
            "arguments",
            buildJsonObject { arguments.forEach { (k, v) -> put(k, v) } },
        )
    }

    @Test
    fun `direct and deferred partition the executor action set`() {
        val direct = HostCapabilityDirectory.DIRECT_ACTIONS.toSet()
        val deferred = HostCapabilityDirectory.DEFERRED_ACTIONS.map { it.name }.toSet()
        assertEquals("DIRECT ∪ DEFERRED 必须覆盖执行器全部动作", executorActions, direct + deferred)
        assertTrue("两个集合不得相交", direct.intersect(deferred).isEmpty())
        assertEquals("deferred 目录不得有重复动作", deferred.size, HostCapabilityDirectory.DEFERRED_ACTIONS.size)
    }

    @Test
    fun `read only actions stay direct so plan mode keeps them usable`() {
        HostCapabilityDirectory.READ_ONLY_ACTIONS.forEach { action ->
            assertTrue("只读动作 $action 应保留在 direct 声明中", action in HostCapabilityDirectory.DIRECT_ACTIONS)
        }
        assertTrue(
            "deferred 动作必须全部非只读（否则 PLAN 委派语义与直接调用不一致）",
            HostCapabilityDirectory.DEFERRED_ACTIONS.none { it.name in HostCapabilityDirectory.READ_ONLY_ACTIONS },
        )
    }

    @Test
    fun `gui assisted set is a subset of known actions`() {
        val known = HostCapabilityDirectory.DIRECT_ACTIONS.toSet() +
            HostCapabilityDirectory.DEFERRED_ACTIONS.map { it.name }.toSet()
        assertTrue(HostCapabilityDirectory.GUI_ASSISTED_ACTIONS.all { it in known })
        assertTrue(HostCapabilityDirectory.READ_ONLY_ACTIONS.all { it in known })
    }

    @Test
    fun `deferred schemas only reference known params and required subset`() {
        HostCapabilityDirectory.DEFERRED_ACTIONS.forEach { action ->
            val schema = HostCapabilityDirectory.schemaFor(action.name)
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            assertEquals("schema 属性必须与动作声明的 params 一致：${action.name}", action.params, properties.keys.toList())
            action.required.forEach { required ->
                assertTrue("required 参数 $required 必须在 params 中", required in action.params)
            }
        }
    }

    @Test
    fun `capability call detection requires use_capability proxy and host server`() {
        assertTrue(
            HostCapabilityDirectory.isCapabilityCall(capabilityArgs("virtual_screen_click", "x" to "1"), "use_capability"),
        )
        assertFalse("server 不是 host", HostCapabilityDirectory.isCapabilityCall(buildJsonObject {
            put("action", "call")
            put("server", "some-mcp")
            put("tool", "x")
        }, "use_capability"))
        assertFalse("action 不是 call", HostCapabilityDirectory.isCapabilityCall(buildJsonObject {
            put("action", "inspect")
            put("server", "host")
        }, "use_capability"))
        assertFalse("代理名不匹配", HostCapabilityDirectory.isCapabilityCall(capabilityArgs("x"), "mcp__host__x"))
    }

    @Test
    fun `flatten maps capability args to equivalent host args`() {
        val flattened = HostCapabilityDirectory.flattenToHostArgs(
            capabilityArgs("virtual_screen_click", "x" to "100", "y" to "200"),
        )
        assertEquals("virtual_screen_click", flattened["action"]?.toString()?.trim('"'))
        assertEquals("100", flattened["x"]?.toString()?.trim('"'))
        assertEquals("200", flattened["y"]?.toString()?.trim('"'))
        assertNull("展平结果不得残留 server/tool 代理字段", flattened["server"])
        assertNull(flattened["tool"])
    }

    @Test
    fun `deferred calls reuse the exact direct host approval matrix`() {
        // ASSISTED 下虚拟屏 GUI 触控与直接调用一样自动放行
        val deferred = policy.decide(
            ApprovalMode.ASSISTED, HarnessTool.MCP,
            capabilityArgs("virtual_screen_click", "x" to "100", "y" to "200"),
            workspace, rawToolName = "use_capability",
        )
        val direct = policy.decide(
            ApprovalMode.ASSISTED, HarnessTool.HOST,
            buildJsonObject { put("action", "virtual_screen_click"); put("x", "100"); put("y", "200") },
            workspace,
        )
        assertFalse("ASSISTED 下虚拟屏点击应自动放行", deferred.required)
        assertEquals(direct.required, deferred.required)

        // 管理动作与直接调用一样需要审批
        val adminDeferred = policy.decide(
            ApprovalMode.ASSISTED, HarnessTool.MCP,
            capabilityArgs("package_uninstall_user", "package" to "com.example.app"),
            workspace, rawToolName = "use_capability",
        )
        val adminDirect = policy.decide(
            ApprovalMode.ASSISTED, HarnessTool.HOST,
            buildJsonObject { put("action", "package_uninstall_user"); put("package", "com.example.app") },
            workspace,
        )
        assertTrue(adminDeferred.required)
        assertEquals(adminDirect.riskLevel, adminDeferred.riskLevel)
        assertEquals("critical", adminDeferred.riskLevel)
    }

    @Test
    fun `plan mode blocks deferred host actions via delegation`() {
        val blocked = policy.planBlock(
            HarnessTool.MCP,
            capabilityArgs("virtual_screen_task", "goal" to "打开设置"),
            rawToolName = "use_capability",
        )
        assertNotNull("只读规划下 deferred 宿主动作必须被拦截", blocked)
    }

    @Test
    fun `router serves host domain without mcp manager`() = runBlocking {
        val invoked = mutableListOf<JsonObject>()
        val router = CapabilityToolRouter(mcpManager = null, argRedactor = { it.replace("hunter2", "[REDACTED]") }) { args, _, _, _ ->
            invoked += args
            true to "ok"
        }
        val metadata = mutableMapOf<String, String>()

        // list：无 MCP 服务时也必须展示宿主能力域
        val listed = router.execute(buildJsonObject { put("action", "list") }, workspace, "call-1", null, "s1", metadata)
        assertTrue(listed.first)
        assertTrue("list 必须包含宿主能力域", "宿主低频能力域" in listed.second)

        // inspect：零依赖返回目录清单
        val inspected = router.execute(
            buildJsonObject { put("action", "inspect"); put("server", "host") },
            workspace, "call-1", null, "s1", metadata,
        )
        assertTrue(inspected.first)
        assertTrue("清单必须包含 deferred 动作", "virtual_screen_task" in inspected.second)
        assertFalse("清单不得包含 direct 高频动作", "device_status" in inspected.second)

        // call：展平后进入宿主执行通道，并在 metadata 留下有界嵌套记录
        val called = router.execute(
            capabilityArgs("virtual_screen_click", "x" to "100", "y" to "200", "token" to "hunter2"),
            workspace, "call-1", "op-1", "s1", metadata,
        )
        assertTrue(called.first)
        assertEquals(1, invoked.size)
        assertEquals("virtual_screen_click", invoked[0]["action"]?.toString()?.trim('"'))
        val log = NestedCalls.read(metadata)
        assertNotNull("成功的内层调用必须留下嵌套记录", log)
        assertEquals(true, log?.complete)
        assertEquals(1, log?.calls?.size)
        val record = log?.calls?.first()
        assertEquals("call-1/1", record?.toolCallId)
        assertEquals("host.virtual_screen_click", record?.name)
        assertEquals(NestedCalls.STATUS_OK, record?.status)
        assertTrue("参数摘要必须脱敏", record?.argumentsPreview?.contains("hunter2") == false)
        assertTrue("参数摘要必须包含实际入参", record?.argumentsPreview?.contains("100") == true)
        assertNull("成功记录不得携带 error", record?.error)

        // call：未知工具拒绝并指引 direct，不得进入执行通道，但留下 blocked 审计
        val unknown = router.execute(
            capabilityArgs("screen_click", "x" to "1", "y" to "2"),
            workspace, "call-2", null, "s1", metadata,
        )
        assertFalse(unknown.first)
        assertTrue("direct 动作应指引直接调用 host 工具", "host 工具" in unknown.second)
        assertEquals("未知工具不得进入执行通道", 1, invoked.size)
        val afterBlocked = NestedCalls.read(metadata)
        assertEquals(2, afterBlocked?.calls?.size)
        val blocked = afterBlocked?.calls?.last()
        assertEquals("call-2/2", blocked?.toolCallId)
        assertEquals(NestedCalls.STATUS_BLOCKED, blocked?.status)
        Unit
    }

    @Test
    fun `nested records capture failures with redacted errors and bounded growth`() = runBlocking {
        val router = CapabilityToolRouter(mcpManager = null, argRedactor = { it.replace("sk-secret123456", "[REDACTED]") }) { _, _, _, _ ->
            false to "执行失败：api key sk-secret123456 已失效"
        }
        val metadata = mutableMapOf<String, String>()
        val failed = router.execute(
            capabilityArgs("virtual_screen_wait", "duration_ms" to "0"),
            workspace, "call-9", null, "s1", metadata,
        )
        assertFalse(failed.first)
        val record = NestedCalls.read(metadata)?.calls?.single()
        assertEquals(NestedCalls.STATUS_ERROR, record?.status)
        assertNotNull(record?.error)
        assertTrue("错误必须脱敏", record?.error?.contains("sk-secret123456") == false)
        assertTrue(record?.error?.contains("[REDACTED]") == true)
    }

    @Test
    fun `validation surface is the executor acceptance surface not the declaration surface`() {
        // 旧式直接调用 deferred 动作（重放 / 沙箱直调 / 历史模仿）在校验层必须仍然合法
        assertTrue(
            ToolSchemaValidator.problemsFor(
                "host",
                buildJsonObject { put("action", "virtual_screen_click"); put("x", 100); put("y", 200) },
            ).isEmpty(),
        )
        assertTrue(
            ToolSchemaValidator.problemsFor(
                "host",
                buildJsonObject { put("action", "virtual_screen_task"); put("goal", "打开设置") },
            ).isEmpty(),
        )
        // 不存在的动作仍然被 enum 拒绝
        assertTrue(
            ToolSchemaValidator.problemsFor("host", buildJsonObject { put("action", "bogus_action") }).isNotEmpty(),
        )
        // direct 动作 + deferred 专属参数都在校验面内（union 参数池）
        assertTrue(
            ToolSchemaValidator.problemsFor(
                "host",
                buildJsonObject { put("action", "screen_capture"); put("path", "shots/a.png") },
            ).isEmpty(),
        )
    }

    @Test
    fun `script action records nested calls per inner invocation`() = runBlocking {
        val invoked = mutableListOf<String>()
        val router = CapabilityToolRouter(mcpManager = null) { args, _, _, _ ->
            invoked += args["action"]?.toString()?.trim('"').orEmpty()
            true to "done-${args["action"]?.toString()?.trim('"')}"
        }
        val metadata = mutableMapOf<String, String>()
        val (ok, output) = router.execute(
            buildJsonObject {
                put("action", "script")
                put(
                    "code",
                    "var r1 = capability.call('host','virtual_screen_wait',{duration_ms:0}); " +
                        "var r2 = capability.call('host','virtual_screen_close',{}); r1.output + '|' + r2.output",
                )
            },
            workspace, "call-5", null, "s1", metadata,
            scriptCall = { server, tool, args -> router.invokeCapability(server, tool, args, workspace, "call-5", null, "s1", metadata) },
        )

        assertTrue(ok)
        assertTrue(output.contains("done-virtual_screen_wait"))
        assertTrue(output.contains("done-virtual_screen_close"))
        assertEquals(2, invoked.size)
        val log = NestedCalls.read(metadata)
        assertEquals("每条内层调用都必须留下嵌套记录", 2, log?.calls?.size)
        assertEquals(
            listOf("host.virtual_screen_wait", "host.virtual_screen_close"),
            log?.calls?.map { it.name },
        )
    }

    @Test
    fun `request mode escalates destructive annotations beyond normal rememberable risk`() {
        val legacyArgs = buildJsonObject { put("name", "query") }
        val escalated = policy.decide(
            ApprovalMode.REQUEST, HarnessTool.MCP, legacyArgs, workspace,
            rawToolName = "mcp__myserver__mytool",
            annotations = McpToolAnnotations(destructiveHint = true),
        )
        assertTrue(escalated.required)
        assertEquals("high", escalated.riskLevel)
        assertTrue("升级必须注明依据", escalated.reason.contains("注解"))

        val plain = policy.decide(
            ApprovalMode.REQUEST, HarnessTool.MCP, legacyArgs, workspace,
            rawToolName = "mcp__myserver__mytool",
        )
        assertEquals("无注解时保持旧行为", "normal", plain.riskLevel)
    }

    @Test
    fun `json schema contract for capability proxy stays stable`() {
        val useCapability = ProviderClient.TOOLS.single { it.function.name == "use_capability" }
        assertTrue(useCapability.function.description.contains("host"))
        assertNotNull(Json.parseToJsonElement(useCapability.function.parameters.toString()).jsonObject)
    }
}
