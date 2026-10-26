package top.tianyan.app.harness

import java.io.EOFException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** No provider calls: replay synthetic wire fixtures over real HTTP chunk framing. */
@RunWith(Parameterized::class)
class SseFixtureReplayTest(private val protocol: String, private val chunkBytes: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}, HTTP chunk bytes={1}")
        fun cases(): List<Array<Any>> = listOf("chat", "responses", "anthropic").flatMap { protocol ->
            listOf(0, 1, 7).map { bytes -> arrayOf<Any>(protocol, bytes) }
        }
    }

    private fun fixture(): String = requireNotNull(javaClass.getResourceAsStream("/sse/$protocol-interleaved.sse"))
        .bufferedReader(Charsets.UTF_8).use { it.readText().replace("\r\n", "\n") }

    private fun replay(body: String, verify: suspend (LlmApiAdapter, ModelConfig) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val response = MockResponse().setHeader("Content-Type", "text/event-stream")
            if (chunkBytes == 0) response.setBody(body) else response.setChunkedBody(body, chunkBytes)
            server.enqueue(response)
            val client = OkHttpClient()
            val json = Json { ignoreUnknownKeys = true }
            val adapter: LlmApiAdapter = when (protocol) {
                "chat" -> ChatApi(client, json)
                "responses" -> ResponsesApi(client, json)
                else -> AnthropicApi(client, json)
            }
            val model = ModelConfig("fixture", if (protocol == "chat") "deepseek" else protocol,
                "offline-test", server.url("/v1").toString(), "fake-key")
            verify(adapter, model)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `reasoning unicode and interleaved tool arguments survive byte fragmentation`() = replay(fixture()) { api, model ->
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val result = api.chatStream(model, emptyList(), onReasoning = { reasoning.append(it) }, onDelta = { text.append(it) })
        assertEquals("你好", text.toString())
        assertEquals("你好", result.content)
        assertEquals("思考", reasoning.toString())
        assertEquals("思考", result.reasoningContent)
        assertEquals(2, result.toolCalls.size)
        val calls = result.toolCalls.associateBy { it.id }
        assertEquals("read", calls.getValue("c1").name)
        assertEquals("""{"path":"你好.txt"}""", calls.getValue("c1").argumentsJson)
        assertEquals("base", calls.getValue("c2").name)
        assertEquals("""{"command":"pwd"}""", calls.getValue("c2").argumentsJson)
    }

    @Test fun `EOF after text and complete arguments cannot become a successful turn`() {
        val withoutEnd = fixture().substringBeforeLast("\ndata:") + "\n"
        replay(withoutEnd) { api, model ->
            val text = StringBuilder()
            try {
                api.chatStream(model, emptyList(), onDelta = { text.append(it) })
                fail("Missing protocol completion must fail")
            } catch (error: EOFException) {
                assertEquals("你好", text.toString())
                assertTrue(HarnessProviderRunner.isTransientFailure(error))
            }
        }
    }

    @Test fun `EOF while tool JSON is incomplete never returns an executable call`() {
        // Keep the first parameter fragment and cut before the next data event.
        val lines = fixture().lines()
        val partial = lines.indexOfFirst { it.contains("arguments") && it.contains("path") || it.contains("partial_json") }
        require(partial >= 0)
        val body = lines.take(partial + 1).joinToString("\n") + "\n\n"
        replay(body) { api, model ->
            try {
                api.chatStream(model, emptyList(), onDelta = {})
                fail("Half JSON must not become a successful tool call")
            } catch (_: EOFException) { }
        }
    }
}
