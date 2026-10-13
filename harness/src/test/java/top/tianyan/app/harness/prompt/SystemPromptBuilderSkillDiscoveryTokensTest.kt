package top.tianyan.app.harness.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.AgentSkill

/**
 * 回归保护：技能段的 token 估算必须跟随渐进式披露 Tier 1 的**摘要段**，
 * 而不是 SKILL.md 正文长度。
 *
 * 背景：UI 用量面板曾按正文长度累加，装 818 个技能（8 MB SKILL.md）时算出 2.7M token，
 * 导致面板永远 100%、新会话也显示满，并让压缩器误判预算已被系统提示吃光。
 */
class SystemPromptBuilderSkillDiscoveryTokensTest {

    private fun skill(id: String, bodyChars: Int) = AgentSkill(
        id = id,
        name = "skill-$id",
        description = "摘要 $id",
        systemPrompt = "x".repeat(bodyChars),
        isEnabled = true,
        isBuiltin = false,
    )

    @Test
    fun `no enabled skills costs nothing`() {
        assertEquals(0, SystemPromptBuilder.estimateSkillDiscoveryTokens(emptyList()))
    }

    @Test
    fun `hundreds of large skills stay inside the tier1 discovery budget`() {
        val skills = (1..818).map { skill("s$it", 60_000) }

        val tokens = SystemPromptBuilder.estimateSkillDiscoveryTokens(skills)

        // Tier 1 段总字符预算 1500，折算成 token 只能是三位数量级；
        // 若这里回到百万级，说明又退化成按正文长度累加了。
        assertTrue("估算应远小于正文规模，实际 $tokens", tokens in 1..2_000)
    }

    @Test
    fun `estimate is independent of skill body length`() {
        val smallBodies = (1..20).map { skill("s$it", 100) }
        val hugeBodies = (1..20).map { skill("s$it", 500_000) }

        assertEquals(
            SystemPromptBuilder.estimateSkillDiscoveryTokens(smallBodies),
            SystemPromptBuilder.estimateSkillDiscoveryTokens(hugeBodies),
        )
    }

    @Test
    fun `estimate matches the section actually injected into the prompt`() {
        val skills = (1..30).map { skill("s$it", 1_000) }

        val section = SystemPromptBuilder.buildSkillDiscoverySection(skills, "")

        assertEquals(
            top.tianyan.app.harness.ContextWindowPolicy.estimateTokens(section),
            SystemPromptBuilder.estimateSkillDiscoveryTokens(skills),
        )
    }
}
