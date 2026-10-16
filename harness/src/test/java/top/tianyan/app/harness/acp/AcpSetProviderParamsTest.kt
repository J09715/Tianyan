package top.tianyan.app.harness.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * `providers/set` 参数构造。
 *
 * 这是 Claude Code 唯一能改端点的路径：claude-agent-acp 不读 ANTHROPIC_* 环境变量，
 * 未配置时会主动清空它们并回退官方 native 端点。密钥必须随 headers 下发，因为引擎
 * 会把 ANTHROPIC_AUTH_TOKEN 置为占位符 "acp-proxy"。
 */
class AcpSetProviderParamsTest {

    private fun JsonObject.header(name: String): String? =
        (this["headers"] as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.content }

    @Test
    fun `targets the fixed main provider with anthropic protocol`() {
        val params = acpSetProviderParams("https://apihub.agnes-ai.com", "sk-test")
        assertEquals("main", (params["providerId"] as JsonPrimitive).content)
        assertEquals("anthropic", (params["apiType"] as JsonPrimitive).content)
        assertEquals("https://apihub.agnes-ai.com", (params["baseUrl"] as JsonPrimitive).content)
    }

    @Test
    fun `carries the real key in both auth header shapes`() {
        val params = acpSetProviderParams("https://apihub.agnes-ai.com", "sk-secret")
        assertEquals("sk-secret", params.header("x-api-key"))
        assertEquals("Bearer sk-secret", params.header("Authorization"))
    }

    @Test
    fun `extra headers are preserved alongside auth`() {
        val params = acpSetProviderParams(
            "https://relay.example.com",
            "sk-secret",
            extraHeaders = mapOf("X-Route" to "team-a"),
        )
        assertEquals("team-a", params.header("X-Route"))
        assertEquals("sk-secret", params.header("x-api-key"))
    }

    @Test
    fun `headers object always exists`() {
        assertNotNull(acpSetProviderParams("https://x.example.com", "k")["headers"])
    }
}
