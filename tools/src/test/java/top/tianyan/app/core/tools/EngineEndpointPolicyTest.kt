package top.tianyan.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineEndpointPolicyTest {

    @Test
    fun `official deepseek openai endpoint maps to anthropic root`() {
        assertEquals(
            "https://api.deepseek.com/anthropic",
            EngineEndpointPolicy.normalizeAnthropic("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic",
            EngineEndpointPolicy.normalizeAnthropic("https://api.deepseek.com/v1/chat/completions"),
        )
    }

    @Test
    fun `minimax maps to anthropic root`() {
        assertEquals(
            "https://api.minimaxi.com/anthropic",
            EngineEndpointPolicy.normalizeAnthropic("https://api.minimaxi.com/v1"),
        )
    }

    @Test
    fun `alibaba model studio maps to apps anthropic`() {
        assertEquals(
            "https://dashscope.aliyuncs.com/apps/anthropic",
            EngineEndpointPolicy.normalizeAnthropic("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    @Test
    fun `generic proxy strips messages endpoint without guessing anthropic path`() {
        assertEquals(
            "https://proxy.example.com",
            EngineEndpointPolicy.normalizeAnthropic("https://proxy.example.com/v1/messages"),
        )
        assertEquals(
            "https://proxy.example.com",
            EngineEndpointPolicy.normalizeAnthropic("https://proxy.example.com/v1"),
        )
    }

    @Test
    fun `openai endpoint normalization keeps v1 root`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            EngineEndpointPolicy.normalizeOpenAi("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1",
            EngineEndpointPolicy.normalizeOpenAi("https://api.openai.com"),
        )
    }

    @Test
    fun `custom full endpoint collapses to v1 root`() {
        assertEquals(
            "http://192.168.1.50:8000/v1",
            EngineEndpointPolicy.normalizeOpenAi("http://192.168.1.50:8000/v1/responses"),
        )
    }
}
