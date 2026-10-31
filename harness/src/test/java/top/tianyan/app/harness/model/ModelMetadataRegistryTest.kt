package top.tianyan.app.harness.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMetadataRegistryTest {
    private val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
    private val textOnly = listOf(Modality.TEXT)
    private val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)

    @Test
    fun gpt5FamilyDisambiguation() {
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("gpt-5"))
        assertEquals(toolReasoning, ModelMetadataRegistry.abilities("gpt-5"))
        // gpt-5-chat 被排除，且没有其他规则命中
        assertEquals(textOnly, ModelMetadataRegistry.inputModalities("gpt-5-chat"))
        assertEquals(emptyList<ModelAbility>(), ModelMetadataRegistry.abilities("gpt-5-chat"))
        // 小版本命中各自的定义
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("gpt-5.1"))
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("gpt-5-mini"))
        // gpt-4o 命中 GPT4O 规则，视觉能力正确
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("gpt-4o"))
        assertEquals(toolReasoning, ModelMetadataRegistry.abilities("gpt-5.1"))
        // gpt-5-chat 不属于任何视觉规则
        assertEquals(emptyList<ModelAbility>(), ModelMetadataRegistry.abilities("gpt-5-chat"))
    }

    @Test
    fun geminiSeries() {
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("gemini-2.5-flash"))
        assertEquals(toolReasoning, ModelMetadataRegistry.abilities("gemini-3-pro"))
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("gemini-4-pro"))
        // 带 image 后缀的是图片输出模型
        assertEquals(visionInput, ModelMetadataRegistry.outputModalities("gemini-2.5-flash-image"))
        assertEquals(textOnly, ModelMetadataRegistry.outputModalities("gemini-2.5-flash"))
        assertEquals(emptyList<ModelAbility>(), ModelMetadataRegistry.abilities("gemini-2.5-flash-image"))
    }

    @Test
    fun claudeSeries() {
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("claude-sonnet-4.5-20250929"))
        assertEquals(toolReasoning, ModelMetadataRegistry.abilities("claude-4-sonnet"))
        assertEquals(1_000_000, ModelMetadataRegistry.contextLength("claude-sonnet-5"))
        assertEquals(1_000_000, ModelMetadataRegistry.contextLength("claude-opus-5"))
        assertEquals(1_000_000, ModelMetadataRegistry.contextLength("claude-sonnet-5-20260305"))
        // 4.x 系列规则未声明上下文长度
        assertNull(ModelMetadataRegistry.contextLength("claude-sonnet-4.5"))
    }

    @Test
    fun specificityPriority() {
        // k2.5 规则比 k2 更具体，得分更高，视觉能力来自 k2.5
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("kimi-k2.5"))
        assertEquals(textOnly, ModelMetadataRegistry.inputModalities("kimi-k2"))
    }

    @Test
    fun magnitudeTokensAreNotVersions() {
        // 8b 是参数量，不能当成 qwen 3.8
        assertEquals(textOnly, ModelMetadataRegistry.inputModalities("qwen3-8b"))
        assertEquals(textOnly, ModelMetadataRegistry.inputModalities("qwen3-30b-a3b"))
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("qwen3-vl-8b-instruct"))
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("qwen3.8-plus"))
        // 3b 不能让 qwen2.5 命中 qwen 3
        assertEquals(emptyList<ModelAbility>(), ModelMetadataRegistry.abilities("qwen2.5-3b-instruct"))
        assertEquals(toolReasoning, ModelMetadataRegistry.abilities("qwen3-8b"))
    }

    @Test
    fun openAIOModels() {
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("o1"))
        assertEquals(toolReasoning, ModelMetadataRegistry.abilities("o3-mini"))
        assertFalse(ModelMetadataRegistry.isKnown("not-a-model-xyz"))
    }

    @Test
    fun glmAndMinimax() {
        assertEquals(textOnly, ModelMetadataRegistry.inputModalities("glm-5"))
        assertEquals(toolReasoning, ModelMetadataRegistry.abilities("glm-5"))
        assertEquals(textOnly, ModelMetadataRegistry.inputModalities("minimax-m2.5"))
        assertEquals(toolReasoning, ModelMetadataRegistry.abilities("minimax-m2.5"))
    }

    @Test
    fun deepseekFamilies() {
        val reasonerAbilities = ModelMetadataRegistry.abilities("deepseek-reasoner")
        assertEquals(reasonerAbilities, ModelMetadataRegistry.abilities("deepseek-v4-flash"))
        assertEquals(reasonerAbilities, ModelMetadataRegistry.abilities("deepseek-v4-pro"))
        assertEquals(visionInput, ModelMetadataRegistry.inputModalities("deepseek-v4-flash-vision-exp"))
        assertEquals(1_000_000, ModelMetadataRegistry.contextLength("deepseek-v4-flash"))
        assertEquals(1_000_000, ModelMetadataRegistry.contextLength("deepseek-v4-pro"))
        assertNull(ModelMetadataRegistry.contextLength("deepseek-v3"))
    }

    @Test
    fun contextLengthUnknownModels() {
        assertNull(ModelMetadataRegistry.contextLength("unknown-model-xyz"))
        assertNull(ModelMetadataRegistry.contextLength("gpt-4o"))
        assertNull(ModelMetadataRegistry.contextLength("claude-4-sonnet"))
    }

    @Test
    fun dslCustomContextLength() {
        val model = defineModel {
            tokens("custom", "ctx")
            contextLength(1_000_000)
        }
        assertEquals(1_000_000, model.contextLength)

        val defaultModel = defineModel {
            tokens("custom", "default")
        }
        assertNull(defaultModel.contextLength)
    }

    @Test
    fun capabilitySnapshot() {
        val known = ModelMetadataRegistry.capabilityOf("deepseek-v4-flash")
        assertTrue(known.known)
        assertTrue(known.tool)
        assertTrue(known.reasoning)
        assertFalse(known.visionInput)
        assertEquals(1_000_000, known.contextTokens)

        val unknown = ModelMetadataRegistry.capabilityOf("totally-unknown-model")
        assertFalse(unknown.known)
    }
}
