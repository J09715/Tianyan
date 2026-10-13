package top.tianyan.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.McpTransportType

/**
 * MCP JSON 导入解析的回归守卫。
 *
 * 背景：外部工具（ProxyPin 等）导出的 mcpServers 配置带 `type` 与 `headers` 字段，
 * 而旧解析器完全不识别——`headers.Authorization` 被丢弃、`type: "http"` 被忽略，
 * 带 Bearer 认证的远程端点接入后握手一律 401。这组测试锁住两类行为：
 *   · Authorization 头 → authToken 的提取（Bearer 前缀剥离 / 裸 token / 兜底字段）；
 *   · type 字段到 transportType 的映射。
 */
class McpJsonParseAuthTest {

    @Test
    fun `mcpServers with bearer header extracts auth token`() {
        val json = """
        {
          "mcpServers": {
            "proxypin_mobile": {
              "type": "http",
              "url": "http://192.168.1.23:9127/mcp",
              "headers": {
                "Authorization": "Bearer 8769c029b9c5dc59bad829fbbf0cda1438b93c6c243cd321"
              }
            }
          }
        }
        """.trimIndent()

        val result = parseMcpJson(json)
        assertTrue("解析应成功", result.isSuccess)
        val server = result.getOrThrow().single()

        assertEquals("proxypin_mobile", server.name)
        assertEquals("http://192.168.1.23:9127/mcp", server.serverUrl)
        assertEquals("Bearer 前缀应被剥离，只留 token", "8769c029b9c5dc59bad829fbbf0cda1438b93c6c243cd321", server.authToken)
        assertEquals("type=http 且有 url 应映射为 SSE（客户端自动协商 Streamable HTTP）", McpTransportType.SSE, server.transportType)
        assertTrue(server.isEnabled)
    }

    @Test
    fun `bearer header is case-insensitive and whitespace-tolerant`() {
        val json = """
        {"mcpServers": {"s": {"url": "http://127.0.0.1:9000/mcp", "headers": {"authorization": "  bearer  tok123  "}}}}
        """.trimIndent()

        val server = parseMcpJson(json).getOrThrow().single()
        assertEquals("tok123", server.authToken)
    }

    @Test
    fun `raw token without bearer prefix is taken verbatim`() {
        val json = """
        {"mcpServers": {"s": {"url": "http://127.0.0.1:9000/mcp", "headers": {"Authorization": "abc123"}}}}
        """.trimIndent()

        val server = parseMcpJson(json).getOrThrow().single()
        assertEquals("abc123", server.authToken)
    }

    @Test
    fun `authToken field and apiKey field are fallback sources`() {
        val byField = parseMcpJson("""{"mcpServers": {"s": {"url": "http://x/mcp", "authToken": "tok-a"}}}""")
            .getOrThrow().single()
        assertEquals("tok-a", byField.authToken)

        val byApiKey = parseMcpJson("""{"mcpServers": {"s": {"url": "http://x/mcp", "apiKey": "tok-b"}}}""")
            .getOrThrow().single()
        assertEquals("tok-b", byApiKey.authToken)
    }

    @Test
    fun `headers auth wins over field fallbacks`() {
        val json = """
        {"mcpServers": {"s": {"url": "http://x/mcp", "authToken": "field-token", "headers": {"Authorization": "Bearer header-token"}}}}
        """.trimIndent()
        val server = parseMcpJson(json).getOrThrow().single()
        assertEquals("header-token", server.authToken)
    }

    @Test
    fun `stdio config with type http but no url stays stdio with empty token`() {
        val json = """
        {"mcpServers": {"s": {"type": "http", "command": "python3", "args": ["-m", "x"]}}}
        """.trimIndent()
        val server = parseMcpJson(json).getOrThrow().single()
        assertEquals(McpTransportType.STDIO, server.transportType)
        assertEquals("", server.authToken)
    }

    @Test
    fun `single object form also extracts auth`() {
        val json = """
        {"name": "proxypin", "type": "http", "url": "http://192.168.1.23:9127/mcp", "headers": {"Authorization": "Bearer single-tok"}}
        """.trimIndent()
        val server = parseMcpJson(json).getOrThrow().single()
        assertEquals("single-tok", server.authToken)
        assertEquals(McpTransportType.SSE, server.transportType)
    }
}
