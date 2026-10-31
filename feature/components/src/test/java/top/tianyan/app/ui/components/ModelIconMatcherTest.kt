package top.tianyan.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelIconMatcherTest {
    @Test
    fun openAiFamily() {
        assertEquals("openai.svg", computeModelIconPath("gpt-4o"))
        assertEquals("openai.svg", computeModelIconPath("gpt-5-mini"))
        assertEquals("openai.svg", computeModelIconPath("openai/gpt-4.1"))
        assertEquals("openai.svg", computeModelIconPath("o3-mini"))
    }

    @Test
    fun chineseVendorRegex() {
        assertEquals("deepseek-color.svg", computeModelIconPath("deepseek-chat"))
        assertEquals("zhipu-color.svg", computeModelIconPath("glm-5"))
        assertEquals("zhipu-color.svg", computeModelIconPath("智谱GLM-4.6"))
        assertEquals("qwen-color.svg", computeModelIconPath("qwen3-vl-8b-instruct"))
        assertEquals("doubao-color.svg", computeModelIconPath("doubao-seed-1.6"))
        assertEquals("doubao-color.svg", computeModelIconPath("豆包-1.6"))
        // 分支顺序：kimi 先于 moonshot
        assertEquals("kimi-color.svg", computeModelIconPath("月之暗面-kimi"))
        assertEquals("moonshot.svg", computeModelIconPath("moonshot-v1"))
    }

    @Test
    fun kimiBareId() {
        assertEquals("kimi-color.svg", computeModelIconPath("kimi-k2.5"))
        assertEquals("kimi-color.svg", computeModelIconPath("k3"))
    }

    @Test
    fun llamaVsOllamaLookbehind() {
        assertEquals("meta-color.svg", computeModelIconPath("llama-4"))
        assertEquals("meta-color.svg", computeModelIconPath("meta-llama-3"))
        // ollama 的 llama 前面是 o，被 (?<!o)llama 排除
        assertEquals("ollama.svg", computeModelIconPath("ollama"))
        // 分支顺序：qwen 先于 ollama
        assertEquals("qwen-color.svg", computeModelIconPath("Ollama-qwen"))
    }

    @Test
    fun westernVendors() {
        assertEquals("claude-color.svg", computeModelIconPath("claude-sonnet-5"))
        // 分支顺序：claude 先于 anthropic，带 claude 字样的 id 用 claude 图标
        assertEquals("claude-color.svg", computeModelIconPath("anthropic/claude-4.5"))
        assertEquals("gemini-color.svg", computeModelIconPath("gemini-2.5-pro"))
        assertEquals("gemini-color.svg", computeModelIconPath("nano-banana-pro"))
        assertEquals("xai.svg", computeModelIconPath("grok-4"))
        assertEquals("mistral-color.svg", computeModelIconPath("mistral-large"))
        assertEquals("minimax-color.svg", computeModelIconPath("minimax-m2.5"))
        assertEquals("nvidia-color.svg", computeModelIconPath("nvidia-nemotron"))
        assertEquals("cohere-color.svg", computeModelIconPath("command-r-plus"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals("deepseek-color.svg", computeModelIconPath("DeepSeek-V3.2"))
        assertEquals("kimi-color.svg", computeModelIconPath("Kimi-K3"))
    }

    @Test
    fun unknownModelReturnsNull() {
        assertNull(computeModelIconPath("totally-unknown-model"))
        assertNull(computeModelIconPath(""))
    }
}
