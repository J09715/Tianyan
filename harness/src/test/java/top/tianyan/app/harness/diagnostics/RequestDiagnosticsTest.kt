package top.tianyan.app.harness.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.*

class RequestDiagnosticsTest {
    private val store = RequestDiagnosticsStore(SecretRedactor())

    private fun record(body: String, session: String = "s", operation: String = "op", attempt: Int = 1) =
        store.record(session, operation, 1, attempt, "completions", body, body.toByteArray().size.toLong(), listOf("private-api-credential"))

    private fun preview(session: String = "s") = store.snapshots.value.getValue(session).last().sections.joinToString { it.preview }

    @Test fun redactsCredentialsAndEchoesAcrossMessagesBeforeClipping() {
        record("""{"model":"m","messages":[{"role":"user","content":"password=\"private-value\""},{"role":"assistant","content":"private-value private-api-credential Bearer abcdefghijklm"}]}""")
        assertFalse(preview().contains("private-value"))
        assertFalse(preview().contains("private-api-credential"))
        assertFalse(preview().contains("abcdefghijklm"))
        assertTrue(preview().contains("[REDACTED]"))
    }

    @Test fun omitsMediaAndOpaqueReasoningWithoutChangingText() {
        record("""{"input":[{"role":"user","content":[{"type":"input_image","image_url":"data:image/png;base64,AAAA"},{"type":"input_text","text":"hello"}]},{"type":"reasoning","encrypted_content":"opaque-data","signature":"opaque-signature"}]}""")
        assertFalse(preview().contains("AAAA"))
        assertFalse(preview().contains("opaque-data"))
        assertFalse(preview().contains("opaque-signature"))
        assertTrue(preview().contains("hello"))
    }

    @Test fun previewLimitsDoNotMasqueradeAsProviderCompaction() {
        record("""{"messages":[{"role":"user","content":"${"x".repeat(90_000)}"}]}""")
        val snapshot = store.snapshots.value.getValue("s").last()
        assertTrue(snapshot.previewTruncated)
        assertTrue(snapshot.sections.sumOf { it.preview.length } <= RequestDiagnosticsStore.MAX_PREVIEW_CHARS)
        assertTrue(snapshot.sections.all { it.preview.length <= RequestDiagnosticsStore.MAX_SECTION_CHARS })
        assertTrue(snapshot.bodyBytes > snapshot.sections.sumOf { it.preview.length })
    }

    @Test fun isolatesSessionsAndRetainsBoundedHistoryAcrossRuns() {
        repeat(RequestDiagnosticsStore.MAX_REQUESTS_PER_SESSION + 1) { record("""{"model":"m$it"}""", attempt = it + 1) }
        record("""{"model":"other"}""", session = "other")
        assertEquals((2..9).toList(), store.snapshots.value.getValue("s").map { it.attempt })
        assertFalse(preview().contains("other"))
        record("""{"model":"new-run"}""", operation = "new")
        assertEquals(RequestDiagnosticsStore.MAX_REQUESTS_PER_SESSION, store.snapshots.value.getValue("s").size)
        assertEquals(listOf("op", "new"), store.snapshots.value.getValue("s").map { it.operationId }.distinct())
        store.removeSession("s")
        assertFalse(store.snapshots.value.containsKey("s"))
        assertTrue(store.snapshots.value.containsKey("other"))
    }

    @Test fun evictsLeastRecentlyRecordedSessions() {
        repeat(8) { record("{}", session = "s$it") }
        record("{}", session = "s0")
        record("{}", session = "new")
        assertEquals(8, store.snapshots.value.size)
        assertTrue(store.snapshots.value.containsKey("s0"))
        assertFalse(store.snapshots.value.containsKey("s1"))
    }

    @Test fun diagnosticFailureDoesNotPreventNetworkRequestOrMutateBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient().withRequestDiagnostics { _, _, _, _ -> error("diagnostic failure") }
            val body = """{"messages":[{"content":"unchanged"}]}"""
            val request = Request.Builder().url(server.url("/v1/chat/completions")).post(body.toRequestBody()).build()
            client.newCall(request).execute().use { assertEquals("ok", it.body.string()) }
            assertEquals(body, server.takeRequest().body.readUtf8())
        }
    }

    @Test fun capturesAllProtocolBodiesAfterConversion() = runBlocking {
        MockWebServer().use { server ->
            val captured = mutableListOf<String>()
            val client = OkHttpClient().withRequestDiagnostics { protocol, body, bytes, secrets ->
                captured += body
                store.record("s", "op", 1, captured.size, protocol, body, bytes, secrets)
            }
            val json = Json { ignoreUnknownKeys = true }
            val model = ModelConfig("test", "test", "test", server.url("/v1").toString(), "private-api-credential", pureChatMode = true)
            val messages = listOf(ApiMessage("system", "instructions"), ApiMessage("user", "hello"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
            ChatApi(client, json).chat(model, messages)
            assertTrue(store.snapshots.value.getValue("s").last().sections.any { it.label == "messages[1] system" })
            assertEquals(captured.last(), server.takeRequest().body.readUtf8())
            server.enqueue(MockResponse().setBody("""{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""))
            ResponsesApi(client, json).chat(model.copy(responseApiEnabled = true), messages)
            assertTrue(store.snapshots.value.getValue("s").last().sections.any { it.label == "instructions" })
            assertEquals(captured.last(), server.takeRequest().body.readUtf8())
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
            AnthropicApi(client, json).chat(model.copy(protocol = ApiProtocol.ANTHROPIC, pureChatMode = false, toolCallMode = ToolCallMode.DISABLED), messages)
            assertTrue(store.snapshots.value.getValue("s").last().sections.any { it.label == "system" })
            assertEquals(captured.last(), server.takeRequest().body.readUtf8())
        }
    }

    @Test fun capturesChangedBodyDuringStreamCompatibilityRetry() = runBlocking {
        MockWebServer().use { server ->
            var attempt = 0
            val captured = mutableListOf<String>()
            val client = OkHttpClient().withRequestDiagnostics { protocol, body, bytes, secrets ->
                captured += body
                store.record("s", "op", 1, ++attempt, protocol, body, bytes, secrets)
            }
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"Extra inputs are not permitted: stream_options"}}"""))
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\ndata: [DONE]\n"))
            val model = ModelConfig("test", "test", "test", server.url("/v1").toString(), "private-api-credential", pureChatMode = true)
            val result = ChatApi(client, Json { ignoreUnknownKeys = true }).chatStream(model, listOf(ApiMessage("user", "hello"))) {}
            assertEquals("ok", result.content)
            assertEquals(2, captured.size)
            captured.forEach { assertEquals(it, server.takeRequest().body.readUtf8()) }
            val history = store.snapshots.value.getValue("s")
            assertEquals(listOf(1, 2), history.map { it.attempt })
            assertTrue(history[0].sections.any { it.label == "stream_options" })
            assertFalse(history[1].sections.any { it.label == "stream_options" })
        }
    }
}
