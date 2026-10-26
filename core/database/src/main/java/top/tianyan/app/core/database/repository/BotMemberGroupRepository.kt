package top.tianyan.app.core.database.repository

import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.bot.BotTeamDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.database.BotMemberGroupDao
import top.tianyan.app.core.database.BotMemberGroupEntity
import top.tianyan.app.core.database.BotMemberGroupMemberEntity
import top.tianyan.app.core.model.bot.*

interface BotMemberGroupRepository {
    fun observeGroups(): Flow<List<BotMemberGroup>>
    suspend fun saveGroup(group: BotMemberGroup)
    fun observeVersions(id: String): Flow<List<TeamPublishedVersion>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun upgrade(id: String, version: Int, groups: Set<String>) { error("升级不可用") }
    suspend fun publicationImpact(id: String): List<TeamUpgradeImpact> = emptyList()
    suspend fun publish(id: String, revision: Int, groups: Set<String>): Int = error("发布不可用")
    suspend fun deleteGroup(id: String)
}

class RoomBotMemberGroupRepository(
    private val dao: BotMemberGroupDao,
) : BotMemberGroupRepository {
    override fun observeGroups(): Flow<List<BotMemberGroup>> =
        combine(dao.observeGroups(), dao.observeMemberships(), dao.observeAllTeamVersions()) { groups, memberships, versions ->
            val byGroup = memberships.groupBy { it.memberGroupId }
            groups.map { group ->
                BotMemberGroup(
                    id = group.id,
                    name = group.name,
                    createdAt = group.createdAt,
                    publishedSnapshot = versions.firstOrNull { it.teamId == group.id && it.version == group.publishedVersion }?.model(),
                    publishedVersion = group.publishedVersion, draftRevision = group.draftRevision,
                    definition = group.definitionJson?.let { Json.decodeFromString<BotTeamDefinition>(it) },
                    memberIds = byGroup[group.id].orEmpty()
                        .sortedBy { it.displayOrder }
                        .map { it.botId },
                )
            }
        }

    override suspend fun saveGroup(group: BotMemberGroup) {
        val now = System.currentTimeMillis()
        dao.saveWithMembers(
            group = BotMemberGroupEntity(group.id, group.name, group.createdAt, group.definition?.let { Json.encodeToString(it) }, group.publishedVersion, group.draftRevision),
            members = group.memberIds.mapIndexed { index, botId ->
                BotMemberGroupMemberEntity(group.id, botId, index, now)
            },
        )
    }

    override fun observeVersions(id: String) = dao.observeVersions(id).map { rows -> rows.map { it.model() } }
    override suspend fun upgrade(id: String, version: Int, groups: Set<String>) = dao.upgradeTeamGroups(id, version, groups)
    override suspend fun publicationImpact(id: String) = dao.teamImpact(id)
    override suspend fun publish(id: String, revision: Int, groups: Set<String>) = dao.publishDefinition(id, revision, groups)
    override suspend fun deleteGroup(id: String) = dao.deleteById(id)
}
