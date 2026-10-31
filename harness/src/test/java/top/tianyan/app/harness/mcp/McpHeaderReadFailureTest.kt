package top.tianyan.app.harness.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.McpServerConfig

class McpHeaderReadFailureTest {
    @Test fun `unreadable headers cannot fall back to anonymous authentication`() = runBlocking {
        val server = McpServerConfig("broken", "Broken", headersReadError = "请重新填写请求头")
        val result = runCatching { resolveMcpHttpHeaders(server, null) }
        assertTrue(result.isFailure)
        assertEquals(server.headersReadError, result.exceptionOrNull()?.message)
    }
}
