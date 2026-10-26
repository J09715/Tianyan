package top.tianyan.app.harness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.RoomAiModelRepository
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.datastore.ProviderPreferences
import top.tianyan.app.core.datastore.SettingsDataStore
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.core.tools.ProviderRepository
import top.tianyan.app.harness.core.LlmApi

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ProviderModelResolverTest.FakeSecrets::class], instrumentedPackages = ["top.tianyan.app.core.security"])
class ProviderModelResolverTest {
    /** Only credential persistence is faked; resolution still reads the real DataStore facade. */
    @Implements(SecretManager::class, isInAndroidSdk = false)
    class FakeSecrets {
        @Implementation fun encrypt(value: String): String = "fixture:$value"
        @Implementation fun decrypt(value: String): String = value.removePrefix("fixture:")
    }
    private lateinit var database: AppDatabase
    private lateinit var models: RoomAiModelRepository
    private lateinit var preferences: AgentPreferences
    private lateinit var resolver: ProviderModelResolver
    private lateinit var providers: ProviderRepository
    private val profile = AiModelEntity(
        id = "profile", name = "gateway", provider = "custom", model = "gpt-4o-mini,gpt-4.1-mini",
        baseUrl = "https://gateway.example/v1", isActive = true, createdAt = 1,
    )

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        models = RoomAiModelRepository(database.aiModelDao())
        val settings = SettingsDataStore(context, SecretManager())
        preferences = AgentPreferences(settings)
        preferences.setDefaultReasoningDepth("auto")
        providers = ProviderRepository(ProviderPreferences(settings))
        resolver = ProviderModelResolver(providers, models, preferences)
    }

    @After fun tearDown() { database.close() }

    @Test fun `external profile never inherits the global provider key`() = runBlocking {
        providers.setApiKey("global-host-secret")
        try {
            models.upsert(profile.copy(provider = "plugin.example.offline", model = "plugin-default"))
            val resolved = resolver.resolveConfigured()
            assertNull(resolved.apiKey)
            assertTrue(resolved.apiKeys.isEmpty())
        } finally { providers.setApiKey("") }
    }

    @Test fun `requested variant is resolved before inferred context capacity`() = runBlocking {
        models.upsert(profile)
        val resolved = resolver.resolveRequestedModel("GPT-4.1-MINI")
        assertEquals("gpt-4.1-mini", resolved.model)
        assertEquals(ModelContextWindows.resolve("gpt-4.1-mini", "custom"), resolved.contextTokens)
        assertNotNull(resolved.contextTokens)
    }

    @Test fun `first response timeout follows the global setting`() = runBlocking {
        models.upsert(profile)
        assertEquals(90_000L, resolver.resolveConfigured().firstResponseTimeoutMs)
        preferences.setFirstResponseTimeoutSeconds(180)
        assertEquals(180_000L, resolver.resolveModel().firstResponseTimeoutMs)
        preferences.setFirstResponseTimeoutSeconds(0)
        assertEquals(0L, resolver.resolveConfigured().firstResponseTimeoutMs)
        preferences.setFirstResponseTimeoutSeconds(30)
        assertEquals(90_000L, resolver.resolveConfigured().firstResponseTimeoutMs)
    }

    @Test fun `explicit limits and protocol flags survive profile resolution`() = runBlocking {
        models.upsert(profile.copy(
            provider = "Claude", responseApiEnabled = true, contextTokens = 12345, maxTokens = 2345,
            toolCallMode = "json", visionEnabled = false, compactionKeepRecentTokens = 3456,
        ))
        val resolved = resolver.resolveConfigured()
        assertEquals(LlmApi.OPENAI_RESPONSES, resolved.api)
        assertEquals(12345, resolved.descriptor.capabilities.contextWindow)
        assertEquals(2345, resolved.descriptor.capabilities.maxOutputTokens)
        assertEquals(3456, resolved.compactionKeepRecentTokens)
        assertTrue(resolved.capabilities.textTools)
        assertFalse(resolved.capabilities.images)
        assertFalse(resolved.capabilities.promptCaching)
    }

    @Test fun `global reasoning disable is ignored when selected serializer cannot encode it`() = runBlocking {
        preferences.setDefaultReasoningDepth("disabled")
        models.upsert(profile.copy(responseApiEnabled = true))
        assertEquals(ReasoningMode.AUTO, resolver.resolveConfigured().reasoningMode)
    }

    @Test fun `global reasoning depth uses messages budget on custom anthropic gateway`() = runBlocking {
        preferences.setDefaultReasoningDepth("high")
        models.upsert(profile.copy(provider = "Claude"))
        val resolved = resolver.resolveConfigured()
        assertEquals(ReasoningMode.ENABLED, resolved.reasoningMode)
        assertEquals(ReasoningEffort.HIGH, resolved.reasoningEffort)
        assertNotNull(ReasoningAdapter.anthropicThinking(resolved))
    }

    @Test fun `explicit reasoning disable takes priority over global depth`() = runBlocking {
        preferences.setDefaultReasoningDepth("high")
        models.upsert(profile.copy(reasoningMode = "disabled"))
        assertEquals(ReasoningMode.DISABLED, resolver.resolveConfigured().reasoningMode)
    }

    @Test fun `missing saved profile and invalid agent selections never silently use active model`() = runBlocking {
        models.upsert(profile)
        assertTrue(runCatching { resolver.resolveSavedModelProfile("missing", null) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { resolver.resolveSavedModelProfile(profile.id, "removed-model") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { resolver.resolveRequestedModel("removed-model") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { resolver.resolveConfigured("missing", null) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { resolver.resolveConfigured(null, "unbound-model") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `session variant outside settings selection uses its bound supplier configuration`() = runBlocking {
        models.upsert(profile.copy(model = "gpt-4o-mini"))
        models.upsert(profile.copy(id = "other", model = "saved-model", isActive = false,
            baseUrl = "https://other.example/v1", secretRef = "discovered-model-test", temperature = 0.3f))
        providers.setModelApiKeys("discovered-model-test", listOf("fixture-other-key"))

        val resolved = resolver.resolveConfigured("other", " gpt-4.1-mini ")

        assertEquals("gpt-4.1-mini", resolved.model)
        assertEquals("https://other.example/v1", resolved.baseUrl)
        assertEquals("fixture-other-key", resolved.apiKey)
        assertEquals(0.3f, resolved.temperature)
        assertEquals(ModelContextWindows.resolve("gpt-4.1-mini", "custom"), resolved.contextTokens)
        assertEquals("saved-model", models.findById("other")!!.model)
        assertEquals("gpt-4o-mini", resolver.resolveConfigured(profile.id).model)
        assertEquals(profile.id, models.activeModel()!!.id)
    }

    @Test fun `explicit supplier and model variant determine the request instead of global activation`() = runBlocking {
        models.upsert(profile)
        models.upsert(profile.copy(id = "other", name = "Other supplier", provider = "Other", model = "small,large",
            baseUrl = "https://other.example/v1", isActive = false))
        val resolved = resolver.resolveConfigured("other", "LARGE")
        assertEquals("large", resolved.model)
        assertEquals("Other", resolved.provider)
        assertEquals("https://other.example/v1", resolved.baseUrl)
        assertEquals(profile.id, models.activeModel()!!.id)
    }
}
