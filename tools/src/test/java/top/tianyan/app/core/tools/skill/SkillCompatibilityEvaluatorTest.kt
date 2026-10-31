package top.tianyan.app.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.skill.CompatibilityLevel
import top.tianyan.app.core.model.skill.PromptTemplateBundle
import top.tianyan.app.core.model.skill.SkillManifest
import top.tianyan.app.core.model.skill.SkillPackage

class SkillCompatibilityEvaluatorTest {

    private val evaluator = SkillCompatibilityEvaluator(toolRegistry = null)

    @Test
    fun evaluate_compatibleWhenDefaultSandboxCommandsRequired() {
        val manifest = SkillManifest(
            id = "test-compat",
            name = "兼容测试",
            description = "依赖基础 bash 与 ps",
            compatibleRuntimes = listOf("debian", "proot"),
            requiredTools = listOf("bash", "ps"),
        )
        val pkg = SkillPackage(manifest = manifest, templates = PromptTemplateBundle())

        val result = evaluator.evaluate(pkg)
        assertEquals(CompatibilityLevel.COMPATIBLE, result.level)
        assertTrue(result.isCompatible)
        assertTrue(result.missingTools.isEmpty())
    }

    @Test
    fun evaluate_partiallyCompatibleWhenMissingTool() {
        val manifest = SkillManifest(
            id = "test-missing-tool",
            name = "缺少工具",
            description = "依赖 rustc 与 ffmpeg",
            compatibleRuntimes = listOf("debian"),
            requiredTools = listOf("rustc", "ffmpeg"),
        )
        val pkg = SkillPackage(manifest = manifest, templates = PromptTemplateBundle())

        val result = evaluator.evaluate(pkg)
        assertEquals(CompatibilityLevel.PARTIALLY_COMPATIBLE, result.level)
        assertTrue(result.isCompatible)
        assertTrue("rustc" in result.missingTools)
        assertTrue("ffmpeg" in result.missingTools)
    }

    @Test
    fun evaluate_incompatibleWhenRuntimeMismatched() {
        val manifest = SkillManifest(
            id = "test-incompatible",
            name = "不匹配环境",
            description = "仅支持 macOS",
            compatibleRuntimes = listOf("darwin", "macos"),
        )
        val pkg = SkillPackage(manifest = manifest, templates = PromptTemplateBundle())

        val result = evaluator.evaluate(pkg)
        assertEquals(CompatibilityLevel.INCOMPATIBLE, result.level)
        assertFalse(result.isCompatible)
        assertTrue(result.unsupportedRuntimes.contains("darwin"))
    }
}
