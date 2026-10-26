package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.AgentSkill
import top.tianyan.app.harness.SkillSuggestion

class SkillSuggestionActionsTest {

    private val customSkill = AgentSkill(
        id = "custom_abc12345",
        name = "旧技能",
        description = "旧描述",
        systemPrompt = "旧正文",
        triggerCommand = "/old",
        isBuiltin = false,
        category = "自定义",
    )

    private val builtinSkill = AgentSkill(
        id = "git_workflow",
        name = "Git 敏捷工作流",
        description = "内置",
        systemPrompt = "内置正文",
        isBuiltin = true,
    )

    private fun suggestion(
        action: String = "create",
        targetSkillId: String? = null,
        trigger: String? = "review",
    ) = SkillSuggestion(
        id = "sug-1",
        createdAt = 1L,
        action = action,
        skillName = " 代码审查 ",
        description = " 沉淀本次审查步骤 ",
        systemPrompt = " 第二人称指导 ",
        triggerCommand = trigger,
        targetSkillId = targetSkillId,
        reason = "本次多步审查可复用",
    )

    @Test
    fun `createNew ignores target and always creates`() {
        val existing = SkillSuggestionActions.resolveExisting(
            suggestion(action = "update", targetSkillId = customSkill.id),
            createNew = true,
            skills = listOf(customSkill),
        )
        assertNull(existing)
        val skill = SkillSuggestionActions.toCustomSkill(suggestion(), existing = null) { "custom_newid" }
        assertEquals("custom_newid", skill.id)
        assertEquals("代码审查", skill.name)
        assertEquals("沉淀本次审查步骤", skill.description)
        assertEquals("第二人称指导", skill.systemPrompt)
        assertEquals("/review", skill.triggerCommand)
        assertTrue(skill.isEnabled)
        assertTrue(!skill.isBuiltin)
        assertEquals("自定义", skill.category)
    }

    @Test
    fun `update copies onto existing custom skill id`() {
        val existing = SkillSuggestionActions.resolveExisting(
            suggestion(action = "update", targetSkillId = customSkill.id),
            createNew = false,
            skills = listOf(customSkill, builtinSkill),
        )
        assertEquals(customSkill.id, existing?.id)
        val skill = SkillSuggestionActions.toCustomSkill(
            suggestion(action = "update", targetSkillId = customSkill.id, trigger = null),
            existing,
        )
        assertEquals(customSkill.id, skill.id)
        assertEquals("代码审查", skill.name)
        assertEquals("/old", skill.triggerCommand)
        assertTrue(!skill.isBuiltin)
    }

    @Test
    fun `update of builtin or missing target falls back to create`() {
        assertNull(
            SkillSuggestionActions.resolveExisting(
                suggestion(action = "update", targetSkillId = builtinSkill.id),
                createNew = false,
                skills = listOf(builtinSkill),
            ),
        )
        assertNull(
            SkillSuggestionActions.resolveExisting(
                suggestion(action = "update", targetSkillId = "missing"),
                createNew = false,
                skills = listOf(customSkill),
            ),
        )
        val created = SkillSuggestionActions.toCustomSkill(
            suggestion(action = "update", targetSkillId = builtinSkill.id),
            existing = null,
        ) { "custom_fallback" }
        assertEquals("custom_fallback", created.id)
        assertTrue(!created.isBuiltin)
    }

    @Test
    fun `blank description uses settings-style default on create`() {
        val skill = SkillSuggestionActions.toCustomSkill(
            SkillSuggestion(
                id = "sug-2",
                createdAt = 1L,
                action = "create",
                skillName = "新技能",
                description = "  ",
                systemPrompt = "正文",
            ),
            existing = null,
        ) { "custom_blank" }
        assertEquals("自定义技能", skill.description)
    }

    @Test
    fun `computeHiddenSuggestionIds combines persisted and local sets`() {
        val hidden = SkillSuggestionActions.computeHiddenSuggestionIds(
            persisted = setOf("id-1"),
            local = setOf("id-2"),
            skills = emptyList(),
            messages = emptyList(),
        )
        assertEquals(setOf("id-1", "id-2"), hidden)
    }

    @Test
    fun `computeHiddenSuggestionIds auto-dismisses create suggestion if skill already exists in repository`() {
        val messages = listOf(
            suggestion(action = "create").copy(id = "sug-create", skillName = "旧技能"),
            suggestion(action = "create").copy(id = "sug-other", skillName = "未存在技能"),
        )
        val hidden = SkillSuggestionActions.computeHiddenSuggestionIds(
            persisted = emptySet(),
            local = emptySet(),
            skills = listOf(customSkill),
            messages = messages,
        )
        assertTrue(hidden.contains("sug-create"))
        assertTrue(!hidden.contains("sug-other"))
    }

    @Test
    fun `computeHiddenSuggestionIds auto-dismisses update suggestion if target skill is already updated`() {
        val updatedTarget = customSkill.copy(systemPrompt = "第二人称指导")
        val messages = listOf(
            suggestion(action = "update", targetSkillId = customSkill.id).copy(
                id = "sug-update-done",
                systemPrompt = "第二人称指导",
            ),
            suggestion(action = "update", targetSkillId = customSkill.id).copy(
                id = "sug-update-pending",
                systemPrompt = "新的未应用的修改",
            ),
        )
        val hidden = SkillSuggestionActions.computeHiddenSuggestionIds(
            persisted = emptySet(),
            local = emptySet(),
            skills = listOf(updatedTarget),
            messages = messages,
        )
        assertTrue(hidden.contains("sug-update-done"))
        assertTrue(!hidden.contains("sug-update-pending"))
    }
}
