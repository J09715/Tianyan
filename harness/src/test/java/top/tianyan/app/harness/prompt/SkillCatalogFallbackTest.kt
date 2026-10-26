package top.tianyan.app.harness.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 技能目录兜底注入契约：
 *
 * 自定义系统提示（customSystemPrompt）普遍不含 {{ACTIVE_SKILLS}} 占位符，会让整份技能目录
 * （模型自主匹配 + load_skill 按需加载的唯一入口）丢失，模型因此"看不见"任何技能。
 * resolveSkillCatalogFallback 负责在"未被 basePrompt 承载"时补注入，且不得重复注入。
 */
class SkillCatalogFallbackTest {

    private val section = "## 可用技能（按需加载）\n- Git 敏捷工作流-进化（/git-workflow）：..." 

    @Test
    fun `自定义提示无占位符时兜底注入技能目录`() {
        val result = resolveSkillCatalogFallback(
            customPromptEnabled = true,
            customPrompt = "你是受托于我的执行者。请用中文干活。",
            skillSection = section,
        )
        assertEquals(section, result)
    }

    @Test
    fun `自定义提示含占位符时不重复注入`() {
        val result = resolveSkillCatalogFallback(
            customPromptEnabled = true,
            customPrompt = "你是执行者。\n\n{{ACTIVE_SKILLS}}\n\n其余规则……",
            skillSection = section,
        )
        assertEquals("", result)
    }

    @Test
    fun `未启用自定义提示时不注入（内置 core_md 已承载占位符）`() {
        val result = resolveSkillCatalogFallback(
            customPromptEnabled = false,
            customPrompt = "",
            skillSection = section,
        )
        assertEquals("", result)
    }

    @Test
    fun `启用但提示为空时不注入（等价于走内置 core_md）`() {
        val result = resolveSkillCatalogFallback(
            customPromptEnabled = true,
            customPrompt = "   ",
            skillSection = section,
        )
        assertEquals("", result)
    }

    @Test
    fun `技能目录为空时兜底返回空串`() {
        val result = resolveSkillCatalogFallback(
            customPromptEnabled = true,
            customPrompt = "无占位符的自定义提示",
            skillSection = "",
        )
        assertEquals("", result)
    }
}
