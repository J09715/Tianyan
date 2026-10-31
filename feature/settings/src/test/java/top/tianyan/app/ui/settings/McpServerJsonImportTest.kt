package top.tianyan.app.ui.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class McpServerJsonImportTest {
    @Test fun `standard and single config preserve custom authentication headers`() {
        val entry = """{"url":"https://example.test/mcp","headers":{"Authorization":"Bearer fake-test-token","X-API-Key":"fake-key"}}"""
        for (raw in listOf(entry, """{"mcpServers":{"web-search":$entry}}""")) {
            val config = parseMcpJson(raw).getOrThrow().single()
            assertEquals("Bearer fake-test-token", config.headers["Authorization"])
            assertEquals("fake-key", config.headers["X-API-Key"])
            assertFalse(config.toExportJsonConfig().contains("fake-test-token"))
            assertFalse(Json.encodeToString(config).contains("fake-key"))
        }
    }

    @Test fun `reject invalid header values and conflicting authentication`() {
        for (headers in listOf("[]", "null", "{\"Authorization\":123}",
            "{\"Authorization\":\"Bearer fake\\r\\nInjected: yes\"}",
            "{\"Host\":\"evil.test\"}", "{\"X-Key\":\"a\",\"x-key\":\"b\"}")) {
            assertTrue(parseMcpJson("""{"url":"https://example.test/mcp","headers":$headers}""").isFailure)
        }
        assertTrue(parseMcpJson("""{"url":"https://example.test/mcp","authMode":"OAUTH","headers":{"authorization":"fake"}}""").isFailure)
    }

    @Test fun `stdio remains compatible`() {
        val config = parseMcpJson("""{"mcpServers":{"local":{"command":"npx","args":["server"],"env":{"KEY":"value"}}}}""").getOrThrow().single()
        assertEquals(listOf("server"), config.args)
        assertEquals(mapOf("KEY" to "value"), config.env)
        assertTrue(config.headers.isEmpty())
    }
}
