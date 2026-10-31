package top.tianyan.app.harness.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpTransportType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpLargeMetadataTest {
    @Test fun `tool results still spill above 512 KiB`() {
        val directory = Files.createTempDirectory("mcp-tool-result-test").toFile()
        try {
            Response.Builder().request(Request.Builder().url("https://example.test/mcp").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("a".repeat(560 * 1024).toResponseBody()).build().use { response ->
                    val payload = readMcpHttpPayload(response, isToolCall = true, directory)
                    assertTrue(payload is McpResponseSizeLimiter.Payload.Spilled)
                    assertEquals(560L * 1024, (payload as McpResponseSizeLimiter.Payload.Spilled).file.length())
                }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `tool directory above 512 KiB loads without spilling`() = runBlocking {
        exercise(560 * 1024, chunked = false) { transport, config ->
            assertEquals(listOf("large_tool"), transport.discover(config).map { it.name })
        }
    }

    @Test fun `chunked tool directory above 512 KiB loads`() = runBlocking {
        exercise(560 * 1024, chunked = true) { transport, config ->
            assertEquals(1, transport.discover(config).size)
        }
    }

    @Test fun `oversize metadata with declared length is rejected`() = runBlocking {
        exercise(4 * 1024 * 1024 + 1, chunked = false) { transport, config ->
            val failure = runCatching { transport.discover(config) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("4096KB"))
        }
    }

    @Test fun `chunked metadata still has a hard limit`() = runBlocking {
        exercise(4 * 1024 * 1024 + 1, chunked = true) { transport, config ->
            val failure = runCatching { transport.discover(config) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("熔断"))
        }
    }

    private suspend fun exercise(
        descriptionBytes: Int,
        chunked: Boolean,
        check: suspend (McpHttpTransport, McpServerConfig) -> Unit,
    ) {
        val server = MockWebServer()
        val spill = Files.createTempDirectory("mcp-metadata-test").toFile()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method != "POST") return MockResponse().setResponseCode(404)
                val rpc = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val method = rpc.getValue("method").jsonPrimitive.content
                if (method == "notifications/initialized") return MockResponse().setResponseCode(202)
                val id = rpc.getValue("id").jsonPrimitive.content
                val result = if (method == "tools/list") {
                    """{"tools":[{"name":"large_tool","description":"${"a".repeat(descriptionBytes)}","inputSchema":{"type":"object"}}]}"""
                } else """{"protocolVersion":"$MCP_PROTOCOL_VERSION"}"""
                val body = """{"jsonrpc":"2.0","id":"$id","result":$result}"""
                return MockResponse().setHeader("Content-Type", "application/json").let {
                    if (chunked) it.setChunkedBody(body, 8192) else it.setBody(body)
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = McpHttpTransport(OkHttpClient(), Json { ignoreUnknownKeys = true },
            AppLogger(context) { it }, spillDirectory = spill)
        val config = McpServerConfig("large", "Large", transportType = McpTransportType.SSE,
            serverUrl = server.url("/mcp").toString())
        try {
            check(transport, config)
            assertTrue("Metadata must not leave spill files", spill.listFiles().orEmpty().isEmpty())
        } finally {
            transport.closeSession(config.id)
            server.shutdown()
            spill.deleteRecursively()
        }
    }
}
