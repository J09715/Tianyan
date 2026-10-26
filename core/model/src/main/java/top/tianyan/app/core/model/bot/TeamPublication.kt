package top.tianyan.app.core.model.bot

import kotlinx.serialization.Serializable

@Serializable
data class TeamPublishedVersion(val teamId: String, val version: Int, val name: String,
    val memberIds: List<String>, val definition: BotTeamDefinition, val publishedAt: Long)

data class TeamUpgradeImpact(val groupId: String, val name: String, val version: Int?,
    val unfinishedTasks: Int, val pendingWork: Int) {
    val canUpgrade: Boolean get() = unfinishedTasks == 0 && pendingWork == 0
}
