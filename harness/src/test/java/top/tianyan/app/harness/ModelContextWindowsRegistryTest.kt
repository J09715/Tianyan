package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.harness.model.ModelMetadataRegistry
import top.tianyan.app.harness.model.ModelsDevCatalog

/**
 * ModelContextWindows 与 ModelMetadataRegistry 的分层解析：
 * models.dev 目录 > token 规则 > 本地离线正则表 > 供应商默认值。
 */
class ModelContextWindowsRegistryTest {
    @Test
    fun tokenRulesOverrideLegacyTable() {
        // claude-sonnet-5 在新规则表里是 1M 上下文，覆盖旧的 claude 一律 200K
        assertEquals(1_000_000, ModelContextWindows.resolve("claude-sonnet-5"))
        assertEquals(1_000_000, ModelContextWindows.resolve("claude-opus-5-5"))
        assertEquals(1_000_000, ModelContextWindows.resolve("deepseek-v4-flash"))
    }

    @Test
    fun legacyTableStillFallsBack() {
        // 新规则表未声明上下文长度的模型回落本地正则表
        assertEquals(1_047_576, ModelContextWindows.resolve("gpt-4.1-mini"))
        assertEquals(128_000, ModelContextWindows.resolve("deepseek-chat"))
        assertEquals(200_000, ModelContextWindows.resolve("claude-sonnet-4.5"))
        assertEquals(1_048_576, ModelContextWindows.resolve("gemini-2.5-pro"))
        assertEquals(400_000, ModelContextWindows.resolve("gpt-5"))
    }

    @Test
    fun normalizesAliasesAndMultiModelField() {
        assertEquals(128_000, ModelContextWindows.resolve("deepseek-ai/DeepSeek-V3.2:free"))
        assertEquals(128_000, ModelContextWindows.resolve("deepseek-v3, gpt-4o"))
    }

    @Test
    fun catalogBeatsAllOfflineTables() {
        val fixture = """
            {"openai": {"models": {"gpt-5": {
                "modalities": {"input": ["text"], "output": ["text"]},
                "tool_call": true, "reasoning": true,
                "limit": {"context": 555000}
            }}}}
        """.trimIndent()
        try {
            ModelMetadataRegistry.installCatalog(ModelsDevCatalog.decode(fixture.byteInputStream()))
            assertEquals(555_000, ModelContextWindows.resolve("gpt-5"))
        } finally {
            ModelMetadataRegistry.installCatalog(null)
        }
        assertEquals(400_000, ModelContextWindows.resolve("gpt-5"))
    }

    @Test
    fun unknownModelFallsBackToProviderDefault() {
        assertEquals(200_000, ModelContextWindows.resolve("unknown-model-xyz", "anthropic"))
        assertEquals(null, ModelContextWindows.resolve("unknown-model-xyz", "unknown-provider"))
        assertEquals(
            ModelContextWindows.DEFAULT_FALLBACK_TOKENS,
            ModelContextWindows.resolveOrDefault("unknown-model-xyz"),
        )
    }
}
