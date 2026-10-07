package top.tianyan.app.harness.redteam

/**
 * 红队技能来源：让可用性体检不依赖具体的技能仓储实现。
 *
 * 上游把体检做成「智能体侧 preflight 跑一次 + 面板侧标状态」共用同一份判定，
 * 这里保持同一约束：协调器只认这个窄接口，仓储如何加载技能与它无关。
 */
interface RedTeamSkillSource {

    data class SkillSource(
        val name: String,
        /** 技能正文；读不到时为空串，体检会给出 `unknown` 而不是误判 `broken`。 */
        val content: String,
        val path: String? = null,
    )

    /** 当前已启用的红队技能。读技能库要走 IO，所以是挂起函数。 */
    suspend fun redTeamSkills(): List<SkillSource>

    /** 缺省实现：没有技能来源时体检只报「没有可体检的技能」。 */
    object Empty : RedTeamSkillSource {
        override suspend fun redTeamSkills(): List<SkillSource> = emptyList()
    }
}