package top.tianyan.app.harness

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.core.LlmApi

class LlmApiRegistryTest {
    @Test fun `external providers follow installed snapshot and never fall back to HTTP`() {
        val adapter = Stub(LlmApi.OPENAI_COMPLETIONS)
        var overrides = mapOf((adapter.api to "plugin.example.offline") to adapter as LlmApiAdapter)
        val registry = LlmApiRegistry(LlmApi.entries.map(::Stub), liveOverrides = { overrides })
        val model = ModelConfig("profile", "plugin.example.offline", "plugin-default", "https://plugin.invalid", null)
        assertSame(adapter, registry.forModel(model))
        overrides = emptyMap()
        assertThrows(IllegalStateException::class.java) { registry.forModel(model) }
    }
    private class Stub(override val api: LlmApi) : LlmApiAdapter {
        override suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult = error("unused")
        override suspend fun chatStream(
            model: ModelConfig, messages: List<ApiMessage>, onReasoning: (String) -> Unit,
            onToolProgress: (ToolCallStreamProgress) -> Unit, onDelta: (String) -> Unit,
        ): ChatResult = error("unused")
    }

    @Test fun `duplicate registrations fail instead of overwriting an adapter`() {
        val adapters = LlmApi.entries.map(::Stub)
        assertTrue(runCatching { LlmApiRegistry(adapters + adapters.first()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `missing protocols fail instead of falling back to completions`() {
        assertTrue(runCatching { LlmApiRegistry(listOf(Stub(LlmApi.OPENAI_COMPLETIONS))) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `same provider can use different wire APIs for its models`() {
        val adapters = LlmApi.entries.map(::Stub)
        val registry = LlmApiRegistry(adapters)
        val model = ModelConfig("profile", "shared", "model", "https://gateway.example", null)
        assertSame(adapters[0], registry.forModel(model))
        assertSame(adapters[1], registry.forModel(model.copy(responseApiEnabled = true, protocol = ApiProtocol.ANTHROPIC)))
        assertSame(adapters[2], registry.forModel(model.copy(protocol = ApiProtocol.ANTHROPIC)))
    }

    @Test fun `stream and ordinary clients share interceptors but retain different timeouts`() {
        val clients = mutableListOf<OkHttpClient>()
        val interceptor = okhttp3.Interceptor { chain -> chain.proceed(chain.request()) }
        ProviderTransport(OkHttpClient.Builder().addInterceptor(interceptor).build(), Json) { client ->
            clients += client
            LlmApiRegistry.builtIn(client, Json)
        }
        assertEquals(2, clients.size)
        assertEquals(300_000, clients[0].callTimeoutMillis)
        assertEquals(300_000, clients[0].readTimeoutMillis)
        assertEquals(0, clients[1].callTimeoutMillis)
        assertEquals(600_000, clients[1].readTimeoutMillis)
        assertSame(clients[0].connectionPool, clients[1].connectionPool)
        clients.forEach { client ->
            assertSame(interceptor, client.interceptors.first())
            assertEquals(1, client.interceptors.filterIsInstance<ProviderRequestBudget>().size)
        }
    }
}
