package top.tianyan.app.harness.redteam

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.core.model.AgentSkill

/**
 * 技能库接缝的落库实现：红队控制台的「技能库」页签直接管应用自己的技能库。
 *
 * 为什么不另起一套文件目录：本移植的红队技能体检（`RepositoryRedTeamSkillSource`）
 * 读的就是 `AgentSkillRepository`。控制台若写另一处目录，就会出现
 * 「面板里保存了、开工体检却说技能不存在」——两处存储等于两套真值。
 *
 * 正文按上游 SKILL.md 的 frontmatter 语义渲染，`whenToUse` / `role` 写在头部，
 * 与目录自动发现读取的元数据键保持一致。
 */
@Singleton
class RepositoryRedTeamSkillStore @Inject constructor(
    private val repository: AgentSkillRepository,
) : RedTeamSkillStore {

    override suspend fun list(): List<RedTeamSkillStore.Skill> =
        repository.allSkills.first().map(::toSkill)

    override suspend fun read(id: String): RedTeamSkillStore.Skill? =
        repository.allSkills.first().firstOrNull { it.id == id }?.let(::toSkill)

    override suspend fun save(skill: RedTeamSkillStore.Skill): RedTeamSkillStore.Skill {
        repository.ensureInitialized()
        // 内置技能不可改：改了就再也回不到出厂文案，而面板上没有「恢复默认」。
        require(!skill.builtin) { "内置技能不可编辑：${skill.name}" }
        val id = skill.id.takeIf { it.isNotBlank() } ?: "custom_" + UUID.randomUUID().toString().take(8)
        val existing = repository.allSkills.first().firstOrNull { it.id == id }
        val rendered = RedTeamSkillStore.renderMarkdown(skill)
        repository.addCustom(
            AgentSkill(
                id = id,
                name = skill.name.trim(),
                description = skill.description.trim().ifBlank { "红队自定义技能" },
                systemPrompt = rendered,
                triggerCommand = existing?.triggerCommand,
                iconName = existing?.iconName ?: "Code",
                isEnabled = skill.enabled,
                isBuiltin = false,
                isImmutable = false,
                category = existing?.category ?: "自定义",
                resourcePath = existing?.resourcePath,
            ),
        )
        return toSkill(
            AgentSkill(
                id = id,
                name = skill.name.trim(),
                description = skill.description.trim().ifBlank { "红队自定义技能" },
                systemPrompt = rendered,
                isEnabled = skill.enabled,
                isBuiltin = false,
                category = existing?.category ?: "自定义",
                resourcePath = existing?.resourcePath,
            ),
        )
    }

    override suspend fun delete(id: String): Boolean {
        repository.ensureInitialized()
        val target = repository.allSkills.first().firstOrNull { it.id == id } ?: return false
        if (target.isBuiltin || target.isImmutable) return false
        repository.deleteCustom(id)
        return true
    }

    /**
     * 正文里带 frontmatter 时按元数据渲染，不带时整篇当正文。
     *
     * 目录自动发现进来的技能正文尾部挂着「【Skill 资源目录】…」提示，那不是用户写的正文；
     * 这里不剥掉——它是技能能否跑起来的关键信息，剥了面板保存一次就把它丢了。
     */
    private fun toSkill(skill: AgentSkill): RedTeamSkillStore.Skill {
        val (meta, body) = RedTeamSkillStore.splitMarkdown(skill.systemPrompt)
        return RedTeamSkillStore.Skill(
            id = skill.id,
            name = meta[RedTeamSkillStore.KEY_NAME] ?: skill.name,
            role = meta[RedTeamSkillStore.KEY_ROLE],
            enabled = meta[RedTeamSkillStore.KEY_ENABLED]?.toBooleanStrictOrNull() ?: skill.isEnabled,
            description = meta[RedTeamSkillStore.KEY_DESCRIPTION] ?: skill.description,
            whenToUse = meta[RedTeamSkillStore.KEY_WHEN_TO_USE],
            body = body.ifBlank { skill.systemPrompt },
            path = skill.resourcePath?.let { "$it/SKILL.md" },
            builtin = skill.isBuiltin || skill.isImmutable,
        )
    }
}
