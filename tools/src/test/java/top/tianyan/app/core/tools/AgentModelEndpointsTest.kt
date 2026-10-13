package top.tianyan.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentModelEndpointsTest {
    @Test
    fun `base URL without v1 prefers v1 models then standard models`() {
        assertEquals(
            listOf(
                "http://192.168.1.50:8000/v1/models",
                "http://192.168.1.50:8000/models",
                "http://192.168.1.50:8000/api/tags",
            ),
            AgentModelEndpoints.forBaseUrl("192.168.1.50:8000"),
        )
    }

    @Test
    fun `base URL with v1 does not duplicate v1`() {
        assertEquals(
            listOf(
                "http://192.168.1.50:8000/v1/models",
                "http://192.168.1.50:8000/models",
                "http://192.168.1.50:8000/api/tags",
            ),
            AgentModelEndpoints.forBaseUrl("http://192.168.1.50:8000/v1"),
        )
    }

    @Test
    fun `local provider uses user LAN base URL instead of loopback default`() {
        val provider = AgentProviderDefinition(
            id = "ollama",
            name = "Ollama 本地",
            baseUrl = "http://127.0.0.1:11434/v1",
            modelsUrl = "http://127.0.0.1:11434/api/tags",
            group = ProviderGroup.LOCAL,
            apiKeyOptional = true,
        )

        assertEquals(
            listOf(
                "http://192.168.1.50:11434/v1/models",
                "http://192.168.1.50:11434/models",
                "http://192.168.1.50:11434/api/tags",
            ),
            AgentModelEndpoints.forProvider(provider, "192.168.1.50:11434/v1"),
        )
    }
}
