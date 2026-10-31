package top.tianyan.app.harness.plugins

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.*
import top.tianyan.app.harness.core.LlmApi

class ExternalPluginProviderTest {
    private val model = ModelConfig("Offline", "plugin.example.sample.offline", "test", "https://plugin.invalid", null)
    @Test fun transcriptAndModelHintsReachExternalProviderWithoutSecrets() = runTest {
        var observed: JsonObject? = null
        val adapter = ExternalPluginProvider(LlmApi.OPENAI_COMPLETIONS) { arguments ->
            observed = arguments
            PluginToolOutput(true, """{"content":"done","reasoning":"thought","toolCalls":[{"id":"call","name":"use_capability","arguments":{"action":"list"}}]}""")
        }
        val messages = listOf(ApiMessage("user", "hello"))
        val result = adapter.chat(model, messages)
        assertEquals("done", result.content)
        assertEquals("thought", result.reasoningContent)
        assertEquals("use_capability", result.toolCalls.single().name)
        assertEquals(setOf("model", "messages", "temperature", "maxTokens", "tools"), observed!!.keys)
        assertEquals("hello", observed!!["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
    }
    @Test fun hostKeysAreRejectedBeforeAnyMcpInvocation() = runTest {
        var calls = 0
        val adapter = ExternalPluginProvider(LlmApi.OPENAI_COMPLETIONS) { calls++; error("Must not run") }
        try { adapter.chat(model.copy(apiKey = "private-host-key"), emptyList()); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }
    @Test fun bufferedStreamingPreservesReasoningAndText() = runTest {
        val adapter = ExternalPluginProvider(LlmApi.OPENAI_COMPLETIONS) { PluginToolOutput(true, """{"content":"result","reasoning":"reason"}""") }
        val deltas = mutableListOf<String>()
        val thoughts = mutableListOf<String>()
        val result = adapter.chatStream(model, emptyList(), thoughts::add, {}, deltas::add)
        assertEquals(listOf("result"), deltas)
        assertEquals(listOf("reason"), thoughts)
        assertEquals("result", result.content)
    }
    @Test fun malformedUnknownAndOversizedResponsesDoNotBecomeToolCalls() = runTest {
        listOf("""{"content":"bad","hostGrant":true}""", """{"toolCalls":{}}""",
            """{"toolCalls":[{"id":true,"name":"run","arguments":{}}]}""").forEach { text ->
            val adapter = ExternalPluginProvider(LlmApi.OPENAI_COMPLETIONS) { PluginToolOutput(true, text) }
            try { adapter.chat(model, emptyList()); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun utf8RequestBudgetRejectsLargeTranscriptsBeforeInvokingMcp() = runTest {
        var calls = 0
        val adapter = ExternalPluginProvider(LlmApi.OPENAI_COMPLETIONS) { calls++; error("Must not run") }
        try { adapter.chat(model, listOf(ApiMessage("user", "中".repeat(100_000)))); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }
}
