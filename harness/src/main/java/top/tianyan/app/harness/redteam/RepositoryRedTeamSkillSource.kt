package top.tianyan.app.harness.redteam

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.AgentSkillRepository

/**
 * 从技能库取红队技能正文供可用性体检。
 *
 * 「技能能列出来 ≠ 能跑」：技能正文里写着本机路径、环境变量、VPS 地址，
 * 只有把正文交给 [top.tianyan.app.core.model.RedTeamSkillAvailability] 才能判出缺什么。
 * 未启用（`isEnabled == false`）的技能不体检——它们本来就不会被加载。
 */
@Singleton
class RepositoryRedTeamSkillSource @Inject constructor(
    private val skillRepository: AgentSkillRepository,
) : RedTeamSkillSource {

    override suspend fun redTeamSkills(): List<RedTeamSkillSource.SkillSource> =
        skillRepository.activeSkills.first().map { skill ->
            RedTeamSkillSource.SkillSource(
                name = skill.name,
                content = skill.systemPrompt,
                // 私有技能包解压目录下有正文文件时，file-missing 判定才有意义。
                path = skill.resourcePath?.let { "$it/SKILL.md" },
            )
        }
}
