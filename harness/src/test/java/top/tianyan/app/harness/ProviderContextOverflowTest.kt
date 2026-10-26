package top.tianyan.app.harness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 侧上下文超限识别（对齐 opencode 的 overflow → compact → replay 闭环第一环）：
 * 各家 400 文案差异大，识别靠消息模式；误报代价只是一次有界的机械压缩尝试，
 * 漏报则用户直接看到失败——因此模式宁滥勿缺，但不允许把配额/参数类错误误判进来。
 */
class ProviderContextOverflowTest {

    @Test
    fun `provider overflow bodies are classified as recoverable`() {
        val bodies = listOf(
            // OpenAI 兼容
            """{"error":{"message":"This model's maximum context length is 8192 tokens. However, you requested 9000 tokens."}}""",
            """{"error":{"code":"context_length_exceeded","message":"reduce the length of the messages"}}""",
            // Anthropic
            """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens > 200000 maximum"}}""",
            """{"type":"error","error":{"message":"input length and `max_tokens` exceed context limit: 250000 tokens > 200000 maximum"}}""",
            // Gemini（OpenAI 兼容端点）
            """{"error":{"code":400,"message":"input token count (130000) exceeds the maximum number of tokens allowed (128000)"}}""",
            // 中文文案
            """{"error":{"message":"输入超出上下文长度限制"}}""",
        )
        bodies.forEach { body ->
            assertTrue("应识别为上下文超限：$body", ProviderClient.isContextOverflowMessage(body))
        }
    }

    @Test
    fun `unrelated error bodies are not classified as overflow`() {
        val bodies = listOf(
            """{"error":{"message":"Invalid parameter: temperature must be between 0 and 2"}}""",
            """{"error":{"message":"model `gpt-x` not found"}}""",
            """{"error":{"message":"invalid api key"}}""",
            """{"error":{"message":"insufficient_quota: you have exceeded your billing limit"}}""",
            """{"error":{"message":"tool schema validation failed"}}""",
        )
        bodies.forEach { body ->
            assertFalse("不应识别为上下文超限：$body", ProviderClient.isContextOverflowMessage(body))
        }
    }

    @Test
    fun `invalid output token errors are not treated as context overflow`() {
        val error = ProviderClient.contextOverflowException(
            400,
            """{"error":{"message":"Invalid max_tokens: must be greater than 0"}}""",
        )
        assertTrue(error is LlmInvalidOutputTokensException)
        assertFalse(error is LlmContextOverflowException)
    }

    @Test
    fun `contextOverflowException returns recoverable type only for overflow bodies`() {
        val overflow = ProviderClient.contextOverflowException(400, """{"error":{"message":"prompt is too long"}}""")
        assertTrue("超限体应抛 LlmContextOverflowException", overflow is LlmContextOverflowException)

        val other = ProviderClient.contextOverflowException(400, """{"error":{"message":"bad tool schema"}}""")
        assertTrue("普通 4xx 仍应是 IllegalStateException", other is IllegalStateException)
        assertFalse(other is LlmContextOverflowException)
    }

    @Test
    fun `http 413 is always classified as recoverable overflow`() {
        val html = ProviderClient.contextOverflowException(
            413,
            "<html><head><title>413 Request Entity Too Large</title></head></html>",
        )
        assertTrue("Nginx HTML 413 必须可自愈", html is LlmContextOverflowException)
        assertTrue(html.message.orEmpty().contains("413"))

        val empty = ProviderClient.contextOverflowException(413, "")
        assertTrue("空 body 的 413 也必须可自愈", empty is LlmContextOverflowException)
    }

    @Test
    fun `emergency fold budget is quarter of clamped budget with floor`() {
        val large = ModelConfig(
            name = "test",
            provider = "openai",
            model = "gpt-test",
            baseUrl = "https://example.invalid/v1",
            apiKey = "sk-test",
            contextTokens = 200_000,
        )
        // 200k × 25% = 50k（clampedBudget 上限 1M 内不截断）
        assertTrue(HarnessProviderRunner.emergencyFoldBudget(large) == 50_000)

        val unknown = ModelConfig(
            name = "test",
            provider = "openai",
            model = "gpt-test",
            baseUrl = "https://example.invalid/v1",
            apiKey = "sk-test",
            contextTokens = null,
        )
        // 元数据缺 contextTokens：默认 128k × 25% = 32k
        assertTrue(HarnessProviderRunner.emergencyFoldBudget(unknown) == 32_000)
    }
}
