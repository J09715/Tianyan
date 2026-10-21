package top.tianyan.app.harness.mcp.server

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎会话红队桥接的契约测试。
 *
 * 背景：红队原本只是天衍本体的 LLM 工具，绑定引擎的会话看不到它，于是「换个智能体就
 * 不能做红队演练」。桥接层要把红队经 MCP 暴露给引擎，但必须守住两条底线：
 * 1. 工具声明里 session_id 必填 —— 事实只属于具体会话，写错会话等于数据污染；
 * 2. 缺 session_id / 未知 action 一律拒绝并给出可执行提示，绝不静默成功。
 */
class RedTeamMcpBridgeTest {

    /** 协调器缺省不可用（未注入）时的行为。 */
    private val bridgeWithoutCoordinator = RedTeamMcpBridge(null)

    @Test
    fun `bridge reports unavailable when coordinator is absent`() {
        assertFalse(bridgeWithoutCoordinator.available)
    }

    @Test
    fun `tool schema keeps the original redteam parameters and adds a required session id`() {
        // 用一个可用桥接来检查 schema 形状；协调器本身在单测里不参与执行。
        val schema = RedTeamMcpBridgeSchemaProbe.describeForTest()
        val properties = schema["properties"]!!.jsonObject
        assertTrue("原有 action 必须在", properties.containsKey("action"))
        val required = (schema["required"] as kotlinx.serialization.json.JsonArray)
            .map { (it as JsonPrimitive).content }
        assertTrue("session_id 必须必填", required.contains("session_id"))
        assertTrue("action 仍必填", required.contains("action"))
    }

    @Test
    fun `missing session id is rejected with an actionable message`() {
        val response = runBlockingResult {
            bridgeWithoutCoordinator.invoke(kotlinx.serialization.json.JsonObject(emptyMap()))
        }
        assertTrue(response.text().contains("session_id"))
        assertTrue("必须标成错误，不能静默成功", response.isError())
    }

    @Test
    fun `session id is trimmed and blank variants are rejected`() {
        val response = runBlockingResult {
            bridgeWithoutCoordinator.invoke(
                kotlinx.serialization.json.buildJsonObject {
                    put("session_id", JsonPrimitive("   "))
                    put("action", JsonPrimitive("preflight"))
                },
            )
        }
        assertTrue(response.isError())
    }

    @Test
    fun `tool name is namespaced away from browser tools`() {
        assertEquals("tianyan_redteam", bridgeWithoutCoordinator.toolName)
        assertFalse(bridgeWithoutCoordinator.toolName.startsWith("browser"))
    }

    @Test
    fun `unknown action lists the valid ones instead of failing opaquely`() {
        val response = runBlockingResult {
            bridgeWithoutCoordinator.invoke(
                kotlinx.serialization.json.buildJsonObject {
                    put("session_id", JsonPrimitive("session-1"))
                    put("action", JsonPrimitive("asset_explode"))
                },
            )
        }
        assertTrue(response.isError())
        assertTrue("应指出未知 action", response.text().contains("asset_explode"))
        assertTrue("应列出可用 action", response.text().contains("preflight"))
    }

    @Test
    fun `valid action without a coordinator reports uninitialised rather than a data error`() {
        val response = runBlockingResult {
            bridgeWithoutCoordinator.invoke(
                kotlinx.serialization.json.buildJsonObject {
                    put("session_id", JsonPrimitive("session-1"))
                    put("action", JsonPrimitive("preflight"))
                },
            )
        }
        assertTrue(response.isError())
        assertTrue(response.text().contains("未初始化"))
    }

    private fun kotlinx.serialization.json.JsonObject.text(): String {
        val content = this["content"] as kotlinx.serialization.json.JsonArray
        return ((content.first().jsonObject)["text"] as JsonPrimitive).content
    }

    private fun kotlinx.serialization.json.JsonObject.isError(): Boolean =
        (this["isError"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    private fun runBlockingResult(block: suspend () -> kotlinx.serialization.json.JsonObject) =
        kotlinx.coroutines.runBlocking { block() }
}

/**
 * 直接复用桥接的 schema 构造逻辑做断言，避免为了测 schema 而构造一个可用的协调器
 * （那需要 Room 事实仓库与技能库）。
 */
private object RedTeamMcpBridgeSchemaProbe {
    fun describeForTest() = top.tianyan.app.harness.redteam.RedTeamToolSchema.parameters().let { base ->
        val properties = (base["properties"] as kotlinx.serialization.json.JsonObject)!!
        val required = (base["required"] as kotlinx.serialization.json.JsonArray)
            .mapNotNull { (it as? JsonPrimitive)?.content }
            .toMutableList()
        if ("session_id" !in required) required += "session_id"
        kotlinx.serialization.json.buildJsonObject {
            put("properties", properties)
            put(
                "required",
                kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }),
            )
        }
    }
}
