package top.tianyan.app.core.database

import androidx.room.*
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.bot.BotTeamDefinition

/** Draft authoring, immutable publication and explicit room upgrades. */
@Dao
interface TeamDefinitionDao : TeamVersionDao {
    @Transaction suspend fun installIndependentDefinition(group: BotMemberGroupEntity, members: List<BotMemberGroupMemberEntity>) {
        require(definitionGroup(group.id) == null) { "团队组已存在" }
        saveDefinition(group, members)
        publishDefinition(group.id, requireNotNull(definitionGroup(group.id)).draftRevision, emptySet())
    }
    @Query("SELECT * FROM bot_member_groups WHERE id = :id")
    suspend fun definitionGroup(id: String): BotMemberGroupEntity?
    @Query("SELECT * FROM bot_member_group_members WHERE memberGroupId = :id ORDER BY displayOrder")
    suspend fun definitionMembers(id: String): List<BotMemberGroupMemberEntity>
    @Query("SELECT id FROM group_chats WHERE teamGroupId = :id")
    suspend fun linkedChats(id: String): List<String>
    @Upsert suspend fun writeDefinitionGroup(group: BotMemberGroupEntity)
    @Query("DELETE FROM bot_member_group_members WHERE memberGroupId = :id")
    suspend fun clearDefinitionMembers(id: String)
    @Insert suspend fun insertDefinitionMember(member: BotMemberGroupMemberEntity)
    @Upsert suspend fun syncChatMember(member: GroupMemberEntity)
    @Query("DELETE FROM group_members WHERE groupId = :groupId AND botId = :botId")
    suspend fun deleteLinkedMember(groupId: String, botId: String)
    @Query("UPDATE group_chats SET adminBotId = :admin, teamGroupId = :teamId, teamVersion = :version WHERE id = :groupId")
    suspend fun setChatTeam(groupId: String, teamId: String, admin: String, version: Int)
    @Query("UPDATE group_deliveries SET status = 'cancelled' WHERE groupId = :groupId AND botId = :botId AND status IN ('pending','submitting','admitted')")
    suspend fun closeRemovedMemberDeliveries(groupId: String, botId: String)

    @Transaction suspend fun saveDefinition(group: BotMemberGroupEntity, members: List<BotMemberGroupMemberEntity>) {
        require(group.name.isNotBlank() && members.map { it.botId }.distinct().size == members.size)
        val definition = group.definitionJson?.let { Json.decodeFromString<BotTeamDefinition>(it) }
        definition?.validate(members.map { it.botId })
        require(definition != null || linkedChats(group.id).isEmpty()) { "被群聊引用的团队组必须保留组织定义" }
        val current = definitionGroup(group.id)
        require(current == null || current.draftRevision == group.draftRevision) { "团队草稿已被修改，请重新打开后编辑" }
        writeDefinitionGroup(group.copy(publishedVersion = current?.publishedVersion ?: 0, draftRevision = (current?.draftRevision ?: 0) + 1))
        clearDefinitionMembers(group.id)
        members.forEach { insertDefinitionMember(it.copy(memberGroupId = group.id)) }
    }

    @Transaction suspend fun publishDefinition(teamId: String, expectedRevision: Int, upgradeGroups: Set<String>): Int {
        val group = requireNotNull(definitionGroup(teamId)) { "团队组不存在" }
        require(group.draftRevision == expectedRevision) { "团队草稿已变化，请刷新发布预览" }
        require(upgradeGroups.all { it in linkedChats(teamId) }) { "群聊引用已变化，请刷新发布预览" }
        val definition = Json.decodeFromString<BotTeamDefinition>(requireNotNull(group.definitionJson) { "请先保存完整团队草稿" })
        val members = definitionMembers(teamId).map { it.botId }
        definition.validate(members)
        val latest = teamVersion(teamId, group.publishedVersion)?.model()
        if (latest != null && latest.name == group.name && latest.memberIds == members && latest.definition == definition) {
            upgradeGroups.forEach { syncVersionToChat(it, teamId, latest.version) }
            return latest.version
        }
        val next = group.publishedVersion + 1
        insertTeamVersion(TeamVersionEntity(teamId, next, group.name, Json.encodeToString(members),
            Json.encodeToString(definition), System.currentTimeMillis()))
        setPublishedVersion(teamId, next)
        upgradeGroups.forEach { syncVersionToChat(it, teamId, next) }
        return next
    }

    @Transaction suspend fun upgradeTeamGroups(teamId: String, version: Int, groups: Set<String>) {
        requireNotNull(teamVersion(teamId, version)) { "发布版本不存在" }
        require(groups.all { linkedTeamId(it) == teamId }) { "群聊引用已变化，请刷新" }
        groups.forEach { syncVersionToChat(it, teamId, version) }
    }

    @Transaction suspend fun syncDefinitionToChat(groupId: String, teamId: String) {
        val group = requireNotNull(definitionGroup(teamId)) { "团队组不存在" }
        require(group.publishedVersion > 0) { "请先在团队组中发布版本" }
        syncVersionToChat(groupId, teamId, group.publishedVersion)
    }

    @Transaction suspend fun syncVersionToChat(groupId: String, teamId: String, version: Int) {
        requireNotNull(teamAdmin(groupId)) { "群聊不存在" }
        val snapshot = requireNotNull(teamVersion(teamId, version)) { "团队发布版本不存在" }.model()
        if (linkedTeamId(groupId) == teamId && linkedVersion(groupId) == version) return
        require(unfinishedTeamTasks(groupId) == 0 && pendingTeamWork(groupId) == 0) {
            "群聊有未结束任务或待处理消息，请完成、核对或取消后再升级团队版本"
        }
        snapshot.definition.validate(snapshot.memberIds)
        val ids = snapshot.memberIds.toSet()
        teamMembers(groupId).filter { it.botId !in ids }.forEach {
            closeRemovedMemberDeliveries(groupId, it.botId)
            deleteLinkedMember(groupId, it.botId)
        }
        setChatTeam(groupId, teamId, snapshot.definition.adminBotId, version)
        snapshot.memberIds.forEachIndexed { index, id -> syncChatMember(GroupMemberEntity(groupId, id, index, snapshot.publishedAt)) }
    }
}
