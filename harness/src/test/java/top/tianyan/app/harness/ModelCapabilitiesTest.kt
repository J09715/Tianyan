package top.tianyan.app.harness

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.core.LlmApi

class ModelCapabilitiesTest {
    private val model = ModelConfig("test", "custom", "model", "https://gateway.example/v1", "secret")

    @Test fun `legacy responses flag takes precedence over anthropic inference`() {
        val resolved = model.copy(protocol = ApiProtocol.ANTHROPIC, responseApiEnabled = true)
        assertEquals(LlmApi.OPENAI_RESPONSES, resolved.api)
        assertFalse(resolved.copy(promptCachingEnabled = true).capabilities.promptCaching)
    }

    @Test fun `pure chat suppresses both tool forms while preserving image preference`() {
        for (mode in ToolCallMode.entries) {
            val resolved = model.copy(toolCallMode = mode, pureChatMode = true)
            assertEquals(ToolCallMode.DISABLED, resolved.effectiveToolCallMode)
            assertFalse(resolved.capabilities.nativeTools)
            assertFalse(resolved.capabilities.textTools)
            assertTrue(resolved.capabilities.images)
        }
        assertFalse(model.copy(visionEnabled = false).capabilities.images)
    }

    @Test fun `text tools and native tools are separate configured capabilities`() {
        val resolved = model.copy(toolCallMode = ToolCallMode.JSON_TEXT)
        assertTrue(resolved.capabilities.textTools)
        assertFalse(resolved.capabilities.nativeTools)
        assertFalse(model.copy(toolCallMode = ToolCallMode.DISABLED).capabilities.textTools)
    }

    @Test fun `reasoning capabilities follow selected serializer on a shared gateway`() {
        val responses = model.copy(responseApiEnabled = true)
        assertFalse(responses.capabilities.reasoningDisable)
        assertTrue(responses.capabilities.reasoningEffort)
        assertTrue(ReasoningAdapter.responsesFields(responses.copy(reasoningMode = ReasoningMode.DISABLED)).isEmpty())
        val anthropic = model.copy(protocol = ApiProtocol.ANTHROPIC, reasoningMode = ReasoningMode.ENABLED)
        assertTrue(anthropic.capabilities.reasoningDisable)
        assertTrue(anthropic.capabilities.reasoningEffort)
        assertNotNull(ReasoningAdapter.anthropicThinking(anthropic))
    }

    @Test fun `only messages adapter enables configured prompt caching`() {
        assertFalse(model.copy(promptCachingEnabled = true).capabilities.promptCaching)
        assertTrue(model.copy(protocol = ApiProtocol.ANTHROPIC, promptCachingEnabled = true).capabilities.promptCaching)
    }

    @Test fun `core descriptor preserves explicit limits without credentials`() {
        val descriptor = model.copy(contextTokens = 12345, maxTokens = 2345).descriptor
        assertEquals(12345, descriptor.capabilities.contextWindow)
        assertEquals(2345, descriptor.capabilities.maxOutputTokens)
        assertFalse(descriptor.toString().contains("secret"))
        assertFalse(descriptor.toString().contains("gateway.example"))
    }
}
