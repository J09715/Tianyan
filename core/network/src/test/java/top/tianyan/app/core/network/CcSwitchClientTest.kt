package top.tianyan.app.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.CcAgentState
import top.tianyan.app.core.model.CcAgentType
import top.tianyan.app.core.model.CcProviderProfile
import top.tianyan.app.core.model.CcSwitchDaemonStatus

class CcSwitchClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testOfflineConnectionGracefulFailure() = runBlocking {
        val client = CcSwitchClient(HttpClientProvider())
        // Port 19871 is not bound, should fail gracefully without throwing uncaught exceptions
        val statusRes = client.getDaemonStatus("admin", "admin123", port = 19871)
        assertTrue(statusRes.isFailure)

        val snapshotRes = client.getProviderSnapshot("admin", "admin123", port = 19871)
        assertTrue(snapshotRes.isFailure)

        val switchRes = client.switchProvider(
            appId = "claude",
            providerId = "deepseek",
            username = "admin",
            password = "admin123",
            csrfToken = "token",
            port = 19871,
        )
        assertTrue(switchRes.isFailure)

        val saveRes = client.saveProvider(
            appId = "claude",
            profile = CcProviderProfile(id = "deepseek", name = "DeepSeek"),
            username = "admin",
            password = "admin123",
            csrfToken = "token",
            port = 19871,
        )
        assertTrue(saveRes.isFailure)
    }

    @Test
    fun testServerManagedAppMapping() {
        // 服务端仅纳管 claude/codex/gemini/opencode/openclaw，其余 Agent 应无模型源入口
        assertEquals("claude", CcAgentType.CLAUDE_CODE.serverAppId)
        assertEquals("codex", CcAgentType.CODEX.serverAppId)
        assertEquals("gemini", CcAgentType.GEMINI_CLI.serverAppId)
        assertEquals("opencode", CcAgentType.OPENCODE.serverAppId)
        assertEquals("openclaw", CcAgentType.OPENCLAW.serverAppId)
        assertEquals(null, CcAgentType.GROK_BUILD.serverAppId)
        assertEquals(null, CcAgentType.HERMES.serverAppId)
        assertEquals(null, CcAgentType.PI.serverAppId)
        assertEquals(5, CcAgentType.serverManaged.size)
    }

    @Test
    fun testSerializationOfModels() {
        val status = CcSwitchDaemonStatus(
            running = true,
            port = 19870,
            proxyEnabled = true,
        )
        val serialized = json.encodeToString(CcSwitchDaemonStatus.serializer(), status)
        val deserialized = json.decodeFromString<CcSwitchDaemonStatus>(serialized)
        assertEquals(19870, deserialized.port)
        assertTrue(deserialized.running)

        val agent = CcAgentState(
            type = CcAgentType.CLAUDE_CODE,
            installed = true,
            currentVersion = "1.0.0",
            activeProviderName = "DeepSeek",
        )
        val agentStr = json.encodeToString(CcAgentState.serializer(), agent)
        val agentBack = json.decodeFromString<CcAgentState>(agentStr)
        assertEquals(CcAgentType.CLAUDE_CODE, agentBack.type)
        assertEquals("DeepSeek", agentBack.activeProviderName)

        val provider = CcProviderProfile(
            id = "deepseek",
            name = "DeepSeek",
            protocol = "OPENAI",
            baseUrl = "https://api.deepseek.com",
            selectedModel = "deepseek-chat",
        )
        val provStr = json.encodeToString(CcProviderProfile.serializer(), provider)
        val provBack = json.decodeFromString<CcProviderProfile>(provStr)
        assertEquals("deepseek", provBack.id)
        assertEquals("https://api.deepseek.com", provBack.baseUrl)
    }
}
