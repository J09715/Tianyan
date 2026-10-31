package top.tianyan.app.core.model.bot

data class TeamTemplateMember(val key: String, val marketId: String, val supervisorKey: String? = null, val name: String? = null, val role: String? = null)

/** Stable template keys are remapped to fresh Bot IDs at import; never shared with other groups. */
data class GroupTeamTemplate(
    val id: String,
    val name: String,
    val description: String,
    val members: List<TeamTemplateMember>,
    val adminKey: String,
    val rules: List<GroupReviewRule>,
) {
    fun validate() {
        require(members.map { it.key }.distinct().size == members.size)
        val team = GroupTeam(adminKey, members.map { it.key }.toSet(),
            members.mapNotNull { member -> member.supervisorKey?.let { member.key to it } }.toMap())
        team.validate()
        GroupGovernance(rules).validate(team)
    }
}
