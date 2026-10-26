package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.AgentSkill
import top.tianyan.app.harness.skill.SkillEvolutionAdvisor
import kotlinx.serialization.json.JsonObject

/**
 * 技能进化顾问（借鉴千问「对话后技能沉淀/进化」）的纯逻辑测试：
 * 提案解析、内容护栏与归一化（update 目标校验/降级、create 去重）。
 */
class SkillEvolutionAdvisorTest {

    @Test
    fun `parses plain and fenced json responses`() {
        val plain = """{"action":"create","name":"周报整理","description":"整理零散记录为周报","trigger":"weekly","system_prompt":"你是周报助手","reason":"工作流可复用"}"""
        val proposal = SkillEvolutionAdvisor.parseAdvisorResponse(plain)
        assertNotNull(proposal)
        assertEquals("create", proposal!!.action)
        assertEquals("周报整理", proposal.name)

        val fenced = "```json\n{\"action\":\"none\"}\n```"
        assertEquals("none", SkillEvolutionAdvisor.parseAdvisorResponse(fenced)?.action)
    }

    @Test
    fun `malformed responses yield null`() {
        assertNull(SkillEvolutionAdvisor.parseAdvisorResponse("没有 JSON"))
        assertNull(SkillEvolutionAdvisor.parseAdvisorResponse("{broken"))
    }

    @Test
    fun `content is bounded by guard rails`() {
        val hugeName = "n".repeat(100)
        val hugePrompt = "p".repeat(20000)
        val raw = "{\"action\":\"create\",\"name\":\"$hugeName\",\"system_prompt\":\"$hugePrompt\"}"
        val proposal = SkillEvolutionAdvisor.parseAdvisorResponse(raw)!!
        assertEquals(20, proposal.name.length)
        assertEquals(8000, proposal.systemPrompt.length)
    }

    @Test
    fun `create proposal dedupes against existing skill names`() {
        val skills = listOf(AgentSkill(id = "s1", name = "周报整理", description = "", systemPrompt = "x"))
        val duplicate = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"create\",\"name\":\"周报整理\",\"system_prompt\":\"y\"}",
        )!!
        assertNull(SkillEvolutionAdvisor.normalizeProposal(duplicate, skills))
    }

    @Test
    fun `update falls back when target missing and downgrades builtin targets`() {
        val custom = AgentSkill(id = "custom_1", name = "旧技能", description = "", systemPrompt = "x", isBuiltin = false)
        val builtin = AgentSkill(id = "builtin_1", name = "内置技能", description = "", systemPrompt = "x", isBuiltin = true)

        val missingTarget = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"update\",\"name\":\"旧技能\",\"system_prompt\":\"y\",\"target_skill_id\":\"gone\"}",
        )!!
        assertEquals("custom_1", SkillEvolutionAdvisor.normalizeProposal(missingTarget, listOf(custom))!!.target_skill_id)

        val builtinTarget = SkillEvolutionAdvisor.parseAdvisorResponse(
            "{\"action\":\"update\",\"name\":\"内置技能\",\"system_prompt\":\"y\",\"target_skill_id\":\"builtin_1\"}",
        )!!
        val normalized = SkillEvolutionAdvisor.normalizeProposal(builtinTarget, listOf(builtin))!!
        assertEquals("create", normalized.action)
        assertNull(normalized.target_skill_id)
        assertTrue(normalized.name.contains("进化"))
    }

    @Test
    fun `digest starts from last user message and bounds tool output`() {
        val hugeOutput = "o".repeat(5000)
        val messages = listOf(
            UserMessage(id = "u0", createdAt = 0, text = "更早的请求"),
            AssistantText(id = "a0", createdAt = 1, text = "更早的回复"),
            UserMessage(id = "u1", createdAt = 2, text = "本次请求"),
            ToolCall(id = "t1", createdAt = 3, tool = HarnessTool.READ, args = JsonObject(emptyMap())),
            ToolResult(id = "r1", createdAt = 4, toolCallId = "t1", success = true, output = hugeOutput),
        )
        val digest = SkillEvolutionAdvisor.buildConversationDigest(messages)!!
        assertTrue(digest.contains("本次请求"))
        assertTrue(!digest.contains("更早的请求"))
        assertTrue(digest.length < 9000)
        assertNull(SkillEvolutionAdvisor.buildConversationDigest(listOf(AssistantText(id = "a", createdAt = 0, text = "hi"))))
    }
}
