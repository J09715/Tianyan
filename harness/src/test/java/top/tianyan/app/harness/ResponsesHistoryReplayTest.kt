package top.tianyan.app.harness

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.session.ApiMessageProjector
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.harness.diagnostics.RequestDiagnosticsStore

class ResponsesHistoryReplayTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val model = ModelConfig(name = "test", provider = "OpenAI", model = "gpt-test",
        baseUrl = "https://example.com/v1", apiKey = "secret", responseApiEnabled = true)
    private val output = json.parseToJsonElement("""[
        {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque"},
        {"type":"message","id":"msg_1","status":"completed","role":"assistant","phase":"commentary",
         "content":[{"type":"output_text","text":"Checking","annotations":[]}]},
        {"type":"function_call","id":"fc_1","status":"completed","call_id":"original_1","name":"read","arguments":"{\"path\":\"a\"}"},
        {"type":"function_call","id":"fc_2","status":"completed","call_id":"original_2","name":"read","arguments":"{\"path\":\"b\"}"}
    ]""").jsonArray
    private val turn get() = ResponsesTurn.capture(model, output)!!
    private fun history(): List<HarnessMessage> = listOf(
        AssistantText("assistant", 1, "Checking", responsesTurn = turn),
        ToolCall("local_1", 2, HarnessTool.READ, buildJsonObject { put("path", "a") }),
        ToolCall("local_2", 3, HarnessTool.READ, buildJsonObject { put("path", "b") }),
        ToolResult("result_2", 4, "local_2", true, "B"),
        ToolResult("result_1", 5, "local_1", false, "A failed"),
    )
    private fun project(history: List<HarnessMessage> = history()) =
        ApiMessageProjector.project(history, ToolCallMode.NATIVE, false)
    private fun body(messages: List<ApiMessage>, config: ModelConfig = model): JsonObject {
        val buffer = Buffer()
        buildResponsesRequest(config, messages, false).body!!.writeTo(buffer)
        return json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }
    private fun input(messages: List<ApiMessage>, config: ModelConfig = model) = body(messages, config)["input"]!!.jsonArray

    @Test fun `persistent transcript replays exact output and maps reordered tool results to provider ids`() {
        val stored = json.encodeToString<List<HarnessMessage>>(history())
        assertFalse(stored.contains("secret"))
        val restored = json.decodeFromString<List<HarnessMessage>>(stored)
        val items = input(project(restored))
        assertEquals(output, JsonArray(items.take(4)))
        assertEquals("original_2", items[4].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("original_1", items[5].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("A failed", items[5].jsonObject["output"]!!.jsonPrimitive.content)
        assertEquals("reasoning.encrypted_content", body(project())["include"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `old persisted messages decode without artifact and never invent reasoning items`() {
        val legacy = json.decodeFromString<HarnessMessage>("""{"type":"assistant","id":"old","createdAt":1,"text":"answer","reasoning":"thinking"}""")
        assertNull((legacy as AssistantText).responsesTurn)
        val items = input(listOf(ApiMessage("assistant", "answer", reasoning_content = "thinking")))
        assertFalse(items.toString().contains("\"type\":\"reasoning\""))
        assertTrue(items.toString().contains("answer"))
    }

    @Test fun `switching model provider endpoint or credentials falls back to local ids`() {
        val changed = listOf(model.copy(model = "other"), model.copy(provider = "other"),
            model.copy(baseUrl = "https://elsewhere.com/v1"), model.copy(apiKey = "rotated"),
            model.copy(customHeaders = "X-Tenant: other"))
        changed.forEach { config ->
            val items = input(project(), config)
            assertFalse(items.toString().contains("opaque"))
            assertTrue(items.toString().contains("local_1"))
        }
        assertTrue(turn.matches(model.copy(baseUrl = model.baseUrl + "/")))
    }

    @Test fun `editing assistant text invalidates native state and preserves the edit`() {
        val edited = history().toMutableList()
        edited[0] = (edited[0] as AssistantText).copy(text = "Edited")
        assertNull(project(edited).first().responsesTurn)
        val items = input(project(edited))
        assertTrue(items.toString().contains("Edited"))
        assertFalse(items.toString().contains("opaque"))
        // Request-size trimming may happen after projection, so the request builder also validates.
        val trimmed = project().toMutableList()
        trimmed[0] = trimmed[0].copy(content = "Trimmed")
        assertFalse(input(trimmed).toString().contains("opaque"))
    }

    @Test fun `missing or pending tool result invalidates the entire batch before sanitizer can synthesize one`() {
        assertNull(project(history().dropLast(1)).first().responsesTurn)
        val pending = history().toMutableList()
        pending[4] = (pending[4] as ToolResult).copy(awaitingApproval = true)
        assertNull(project(pending).first().responsesTurn)
        assertFalse(input(project().dropLast(1)).toString().contains("opaque"))
        assertFalse(input(sanitizeApiTranscript(project().dropLast(1))).toString().contains("opaque"))
    }

    @Test fun `intervening image message cannot turn sanitizer placeholders into valid native results`() {
        val source = project().toMutableList()
        source.add(2, ApiMessage("user", "image", imageUrls = listOf("data:image/png;base64,a")))
        assertFalse(input(sanitizeApiTranscript(source)).toString().contains("opaque"))
    }

    @Test fun `changed tool arguments invalidate artifact but equivalent json spacing does not`() {
        val original = project().first()
        val spaced = original.copy(tool_calls = original.tool_calls!!.map {
            it.copy(function = it.function.copy(arguments = " { \"path\" : \"${if (it.id == "local_1") "a" else "b"}\" } "))
        })
        assertNotNull(ResponsesReplay.callMapping(turn, spaced))
        val changed = spaced.copy(tool_calls = spaced.tool_calls!!.map {
            it.copy(function = it.function.copy(arguments = "{}"))
        })
        assertNull(ResponsesReplay.callMapping(turn, changed))
    }

    @Test fun `duplicate native ids in later rounds fall back without duplicating opaque output`() {
        val items = input(project() + project())
        assertEquals(1, items.count { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "reasoning" })
        assertEquals(2, items.count { (it as? JsonObject)?.get("call_id")?.jsonPrimitive?.content == "local_1" })
    }

    @Test fun `unsupported output types and incomplete items are not replayed`() {
        for (extra in listOf("""{"type":"web_search_call","id":"ws_1"}""",
            """{"type":"reasoning","status":"in_progress","encrypted_content":"partial"}""")) {
            val invalid = ResponsesTurn.capture(model, JsonArray(output + json.parseToJsonElement(extra)))!!
            assertNull(ResponsesReplay.callMapping(invalid, project().first()))
        }
    }

    @Test fun `opaque payload counts towards byte and token budgets and can be omitted independently`() {
        val plain = project().map { it.copy(responsesTurn = null) }
        val native = project()
        assertTrue(ContextWindowPolicy.estimateApiPayloadBytes(native) >=
            ContextWindowPolicy.estimateApiPayloadBytes(plain) + turn.payloadBytes)
        assertTrue(ContextWindowPolicy.estimateApiMessages(native) > ContextWindowPolicy.estimateApiMessages(plain))
        val bounded = ContextWindowPolicy.shrinkApiMessagesToByteBudget(native,
            ContextWindowPolicy.estimateApiPayloadBytes(plain))
        assertNull(bounded.first().responsesTurn)
        assertEquals(plain, bounded)
        assertNotNull(native.first().responsesTurn)
    }

    @Test fun `oversized artifacts do not enter persistent history`() {
        val oversized = buildJsonArray { add(buildJsonObject {
            put("type", "reasoning"); put("encrypted_content", "x".repeat(ResponsesTurn.MAX_PAYLOAD_BYTES))
        }) }
        assertNull(ResponsesTurn.capture(model, oversized))
    }

    @Test fun `tool only response uses empty assistant anchor and keeps native reasoning`() {
        val toolOnly = ResponsesTurn.capture(model, JsonArray(output.filter { it.jsonObject["type"]!!.jsonPrimitive.content != "message" }))!!
        val source = history().toMutableList()
        source[0] = (source[0] as AssistantText).copy(text = "", responsesTurn = toolOnly)
        assertEquals(toolOnly.output, input(project(source)).take(3))
        assertNull(ApiMessageProjector.project(source, ToolCallMode.JSON_TEXT, false).first().responsesTurn)
    }

    @Test fun `replayed encrypted output never enters request diagnostics preview`() {
        val diagnostics = RequestDiagnosticsStore(SensitiveDataRedactor { it })
        val requestBody = body(project()).toString()
        diagnostics.record("s", "run", 1, 1, "responses", requestBody, requestBody.length.toLong())
        val preview = diagnostics.snapshots.value["s"]!!.single().sections.joinToString { it.preview }
        assertFalse(preview.contains("opaque"))
        assertTrue(preview.contains("BINARY_OR_OPAQUE_OMITTED"))
        assertTrue(preview.contains("original_1"))
    }
}
