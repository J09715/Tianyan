package top.tianyan.app.core.model.bot

import kotlinx.serialization.Serializable

/** Authored once in a reusable team group. Chats own conversation state, not this definition. */
@Serializable
data class BotTeamDefinition(
    val adminBotId: String,
    val supervisors: Map<String, String> = emptyMap(),
    val governance: GroupGovernance = GroupGovernance(),
) {
    fun team(memberIds: Collection<String>) = GroupTeam(adminBotId, memberIds.toSet(), supervisors)
    fun validate(memberIds: Collection<String>) {
        val team = team(memberIds)
        team.validate()
        governance.validate(team)
    }
}
