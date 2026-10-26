package top.tianyan.app.harness.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Pipe
import okio.buffer
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
class McpLegacyHeadersTest {
    @Test fun `legacy event stream and all message posts use custom headers`() = runBlocking {
        val pipe = Pipe(8192)
        val sink = pipe.sink.buffer()
        val methods = CopyOnWriteArrayList<String>()
        val observed = CopyOnWriteArrayList<String?>()
        val server = MockWebServer()
        server.start()
        sink.writeUtf8("event: endpoint\ndata: /messages\n\n").flush()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                observed += request.getHeader("Authorization")
                val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val method = payload.getValue("method").jsonPrimitive.content
                methods += method
                if (method != "notifications/initialized") {
                    val result = when (method) {
                        "initialize" -> """{"protocolVersion":"$MCP_PROTOCOL_VERSION"}"""
                        "tools/list" -> """{"tools":[]}"""
                        else -> """{"content":[{"type":"text","text":"ok"}]}"""
                    }
                    val id = payload.getValue("id").jsonPrimitive.content
                    sink.writeUtf8("""data: {"jsonrpc":"2.0","id":"$id","result":$result}""" + "\n\n").flush()
                }
                return MockResponse().setResponseCode(202)
            }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().method != "GET") return@addInterceptor chain.proceed(chain.request())
            observed += chain.request().header("Authorization")
            assertEquals("text/event-stream", chain.request().header("Accept"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = pipe.source.buffer()
                }).build()
        }.build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = McpHttpTransport(client, Json { ignoreUnknownKeys = true }, AppLogger(context) { it })
        val config = McpServerConfig("legacy", "Legacy", transportType = McpTransportType.SSE,
            serverUrl = server.url("/sse").toString(), headers = mapOf("authorization" to "Bearer fake-legacy"))
        try {
            transport.discover(config)
            assertTrue(transport.execute(config, "sample", buildJsonObject {}).first)
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), methods)
            assertEquals(List(5) { "Bearer fake-legacy" }, observed)
        } finally {
            transport.closeSession(config.id)
            sink.close()
            server.shutdown()
        }
    }
}
