package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ResponsesNativeStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ResponsesApi
    private val json = Json { ignoreUnknownKeys = true }
    private val reasoning = json.parseToJsonElement("""{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"complete-secret"}""")
    private val message = json.parseToJsonElement("""{"type":"message","id":"msg_1","role":"assistant","phase":"final_answer","status":"completed","content":[{"type":"output_text","text":"Hello","annotations":[]}]}""")
    private val call = json.parseToJsonElement("""{"type":"function_call","id":"fc_1","call_id":"provider_1","name":"read","arguments":"{\"path\":\"a\"}","status":"completed"}""")
    private val output get() = JsonArray(listOf(reasoning, message, call))
    private fun model() = ModelConfig(name = "test", provider = "OpenAI", model = "gpt-test",
        baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "key", responseApiEnabled = true)
    @Before fun setUp() {
        server = MockWebServer(); server.start()
        api = ResponsesApi(OkHttpClient(), json)
    }
    @After fun tearDown() { server.shutdown() }
    private fun delta(text: String) = buildJsonObject { put("type", "response.output_text.delta"); put("delta", text) }
    private fun done(index: Int, item: JsonElement) = buildJsonObject {
        put("type", "response.output_item.done"); put("output_index", index); put("item", item)
    }
    private fun completed(items: JsonArray? = output) = buildJsonObject {
        put("type", "response.completed"); put("response", buildJsonObject {
            put("status", "completed")
            items?.let { put("output", it) }
            put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 5) })
        })
    }
    private fun enqueue(vararg events: JsonObject) {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "data: $it\n\n" }))
    }

    @Test fun `nonstream capture keeps exact encrypted output phase and ids`() = runBlocking {
        server.enqueue(MockResponse().setBody(buildJsonObject {
            put("status", "completed"); put("output", output)
        }.toString()))
        val result = api.chat(model(), emptyList())
        assertEquals(output.toList(), result.responsesTurn!!.output)
        assertEquals("Hello", result.content)
        assertEquals("provider_1", result.toolCalls.single().id)
    }

    @Test fun `completed only gateway delivers text once and supplies tools and opaque state`() = runBlocking {
        enqueue(completed())
        val deltas = StringBuilder()
        val result = api.chatStream(model(), emptyList(), onDelta = { deltas.append(it) })
        assertEquals("Hello", deltas.toString())
        assertEquals("Hello", result.content)
        assertEquals("provider_1", result.toolCalls.single().id)
        assertEquals(output.toList(), result.responsesTurn!!.output)
        assertEquals(10L, result.usage.inputTokens)
    }

    @Test fun `normal deltas are not duplicated by final output and missing tail is filled`() = runBlocking {
        for (text in listOf("Hello", "Hel")) {
            enqueue(delta(text), completed())
            val deltas = StringBuilder()
            val result = api.chatStream(model(), emptyList(), onDelta = { deltas.append(it) })
            assertEquals("Hello", deltas.toString())
            assertEquals("Hello", result.content)
        }
    }

    @Test fun `partial think tag buffered text is not duplicated when final output arrives`() = runBlocking {
        val literal = buildJsonArray { add(buildJsonObject {
            put("type", "message"); put("role", "assistant"); put("content", buildJsonArray {
                add(buildJsonObject { put("type", "output_text"); put("text", "Hello <3") })
            })
        }) }
        enqueue(delta("Hello <"), completed(literal))
        val deltas = StringBuilder()
        val result = api.chatStream(model(), emptyList(), onDelta = { deltas.append(it) })
        assertEquals("Hello <3", deltas.toString())
        assertEquals("Hello <3", result.content)
    }

    @Test fun `done items replay in output index order and added partial encryption is discarded`() = runBlocking {
        val added = buildJsonObject {
            put("type", "response.output_item.added"); put("output_index", 0)
            put("item", buildJsonObject { put("type", "reasoning"); put("id", "rs_1"); put("encrypted_content", "partial") })
        }
        enqueue(added, done(2, call), done(1, message), done(0, reasoning), completed(null))
        val result = api.chatStream(model(), emptyList(), onDelta = {})
        assertEquals(output.toList(), result.responsesTurn!!.output)
        assertFalse(result.responsesTurn.toString().contains("partial"))
    }

    @Test fun `eof and output index gaps never create replay artifacts`() = runBlocking {
        enqueue(delta("Hello"), done(0, reasoning))
        try {
            api.chatStream(model(), emptyList(), onDelta = {})
            fail("EOF must not become a successful partial reply")
        } catch (_: java.io.EOFException) { }
        enqueue(done(0, reasoning), done(2, call), completed(null))
        assertNull(api.chatStream(model(), emptyList(), onDelta = {}).responsesTurn)
    }

    @Test fun `incomplete nonstream response keeps display text without native replay state`() = runBlocking {
        server.enqueue(MockResponse().setBody(buildJsonObject {
            put("status", "incomplete"); put("output", output)
        }.toString()))
        val result = api.chat(model(), emptyList())
        assertEquals("Hello", result.content)
        assertNull(result.responsesTurn)
    }
}
