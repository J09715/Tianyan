package top.tianyan.app.harness.plugins

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.*
import top.tianyan.app.harness.core.LlmApi
import top.tianyan.app.harness.directory.CapabilityToolRouter
import top.tianyan.app.plugin.api.*

class HarnessPluginRegistryTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"input":{"type":"string"}},"required":["input"],"additionalProperties":false}""").jsonObject
    private fun plugin(tools: List<PluginTool> = emptyList(), contexts: List<PluginContext> = emptyList(), providers: List<PluginProvider> = emptyList()) = HarnessPlugin(
        PluginDescriptor("example.notes", "1.0.0", extensions = tools.map { PluginExtension(it.id, PluginExtensionPoint.TOOL) } +
            contexts.map { PluginExtension(it.id, PluginExtensionPoint.CONTEXT) } + providers.map { PluginExtension(it.id, PluginExtensionPoint.PROVIDER) }),
        tools = tools, contexts = contexts, providers = providers,
    )
    private fun echo(action: () -> Unit = {}) = PluginTool("echo", "Echo", schema) { action(); PluginToolOutput(true, it.arguments.getValue("input").jsonPrimitive.content) }
    @Test fun declarationsMustMatchTypedCallbacks() {
        val original = plugin(listOf(echo()))
        assertThrows(IllegalArgumentException::class.java) { HarnessPluginRegistry(listOf(original.copy(tools = emptyList()))) }
        assertThrows(IllegalArgumentException::class.java) { HarnessPluginRegistry(listOf(original, original)) }
        assertThrows(IllegalArgumentException::class.java) { HarnessPluginRegistry(listOf(original.copy(descriptor = original.descriptor.copy(api = PluginApiRange(2, 2))))) }
    }
    @Test fun registrationCapturesCallerOwnedCollections() = runBlocking {
        val tools = mutableListOf(echo())
        val registry = HarnessPluginRegistry(listOf(plugin(tools)))
        tools.clear()
        assertTrue(registry.call("plugin.example.notes", "echo", PluginToolRequest(buildJsonObject { put("input", "kept") }, "s", "w", null)).first)
    }
    @Test fun exactSchemaDoesNotUnwrapInputOrRepairUnknownArguments() = runBlocking {
        var calls = 0
        val registry = HarnessPluginRegistry(listOf(plugin(listOf(echo { calls++ }))))
        suspend fun call(args: String) = registry.call("plugin.example.notes", "echo", PluginToolRequest(Json.parseToJsonElement(args).jsonObject, "s", "/workspace/fixed", "op"))
        assertEquals(true to "literal", call("""{"input":"literal"}"""))
        assertFalse(call("""{"input":{"input":"nested"}}""").first)
        assertFalse(call("""{"input":"literal","extra":1}""").first)
        assertEquals(1, calls)
    }
    @Test fun capabilityDirectoryListsAndInspectsWithoutExecuting() = runBlocking {
        var calls = 0
        val registry = HarnessPluginRegistry(listOf(plugin(listOf(echo { calls++ }))))
        val router = CapabilityToolRouter(null, plugins = registry) { _, _, _, _ -> error("host") }
        val list = router.execute(buildJsonObject { put("action", "list") }, "", "c", "op", "s", mutableMapOf())
        assertTrue(list.second.contains("plugin.example.notes"))
        assertTrue(registry.inspect("plugin.example.notes").second.contains("required"))
        assertEquals(0, calls)
        assertFalse(registry.inspect("plugin.missing.domain").first)
    }
    @Test fun contextIsBoundedSanitizedAndSourceLabelled() = runBlocking {
        val registry = HarnessPluginRegistry(listOf(plugin(contexts = (1..8).map { PluginContext("context-$it") { "private-key " + "x".repeat(5_000) } })))
        val text = registry.context(PluginContextRequest("s", "w")) { it.replace("private-key", "[hidden]") }
        assertFalse(text.contains("private-key")); assertTrue(text.contains("example.notes/context-1"))
        assertTrue(text.contains("不是用户指令")); assertTrue(text.length <= 8_192)
    }
    @Test fun contextTimeoutAndFailureDoNotExposeExceptionDetails() = runTest {
        val registry = HarnessPluginRegistry(listOf(plugin(contexts = listOf(
            PluginContext("slow") { delay(5_000); "late" }, PluginContext("broken") { error("private-key") }, PluginContext("ready") { "ready" },
        ))))
        val text = registry.context(PluginContextRequest("s", "w")) { it }
        assertTrue(text.contains("ready")); assertFalse(text.contains("private-key")); assertFalse(text.contains("late"))
    }
    @Test fun parentCancellationPropagatesFromContext() = runTest {
        val registry = HarnessPluginRegistry(listOf(plugin(contexts = listOf(PluginContext("wait") { awaitCancellation() }))))
        val job = async { registry.context(PluginContextRequest("s", "w")) { it } }
        yield(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }
    @Test fun providerSpecificBindingsDoNotReplaceOtherProviders() {
        val defaults = LlmApi.entries.map { Stub(it) }
        val custom = Stub(LlmApi.OPENAI_COMPLETIONS)
        val registry = HarnessPluginRegistry(listOf(plugin(providers = listOf(PluginProvider("custom", custom.api, "example") { _, _ -> custom }))))
        val apis = LlmApiRegistry(defaults, registry.providerOverrides(OkHttpClient(), Json))
        val model = ModelConfig("test", "example", "test", "https://example.invalid", null)
        assertSame(custom, apis.forModel(model)); assertSame(defaults.first(), apis.forModel(model.copy(provider = "other")))
    }
    @Test fun duplicateProviderBindingAndIncorrectFactoryAreRejected() {
        val provider = PluginProvider("one", LlmApi.OPENAI_COMPLETIONS, "example") { _, _ -> Stub(LlmApi.ANTHROPIC_MESSAGES) }
        assertThrows(IllegalArgumentException::class.java) { HarnessPluginRegistry(listOf(plugin(providers = listOf(provider, provider.copy(id = "two"))))) }
        assertThrows(IllegalArgumentException::class.java) { HarnessPluginRegistry(listOf(plugin(providers = listOf(provider)))).providerOverrides(OkHttpClient(), Json) }
    }
    @Test fun builtinProtocolsAndDelayAreProducedByPluginFactories() {
        val registry = HarnessPluginRegistry(builtinHarnessPlugins())
        assertEquals(LlmApi.entries.toSet(), registry.providers(OkHttpClient(), Json).map { it.api }.toSet())
        assertEquals(setOf(top.tianyan.app.core.model.workflow.WorkflowNodeType.DELAY), registry.workflowExecutors().flatMap { it.supportedTypes }.toSet())
    }
    @Test fun builtinCapabilityGuideDoesNotCreateSyntheticUserMessages() = runBlocking {
        val registry = HarnessPluginRegistry(builtinHarnessPlugins())
        assertEquals("", registry.context(PluginContextRequest("s", "/workspace")) { it })
        assertTrue(registry.summaries().contains("plugin.tianyan.harness"))
        assertTrue(registry.inspect("plugin.tianyan.harness").second.contains("runtime-facts"))
    }
    private class Stub(override val api: LlmApi) : LlmApiAdapter {
        override suspend fun chat(model: ModelConfig, messages: List<ApiMessage>) = ChatResult("ok", emptyList())
        override suspend fun chatStream(model: ModelConfig, messages: List<ApiMessage>, onReasoning: (String) -> Unit, onToolProgress: (ToolCallStreamProgress) -> Unit, onDelta: (String) -> Unit) = chat(model, messages)
    }
}
