package top.tianyan.app.core.database

import androidx.room.*
import top.tianyan.app.core.model.bot.GroupGovernance

@Dao
interface GroupTemplateDao : GroupGovernanceDao, TeamDefinitionDao {
    @Query("SELECT * FROM group_chats WHERE id = :id LIMIT 1")
    suspend fun templateGroup(id: String): GroupChatEntity?
    @Upsert suspend fun publishTemplateGroup(group: GroupChatEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTemplateMember(member: GroupMemberEntity)

    @Transaction suspend fun saveChatConfiguration(group: GroupChatEntity) {
        val current = templateGroup(group.id)
        if (current?.teamGroupId != null) publishTemplateGroup(group.copy(adminBotId = current.adminBotId, teamGroupId = current.teamGroupId, teamVersion = current.teamVersion))
        else {
            require(group.teamGroupId == null) { "请使用团队组引用入口建群" }
            publishTemplateGroup(group)
        }
    }

    /** 群管理员移交：不允许留无管理员的群，也不允许移交给非成员。 */
    @Transaction suspend fun transferAdmin(groupId: String, newAdminBotId: String) {
        val current = requireNotNull(templateGroup(groupId)) { "群聊不存在" }
        require(current.teamGroupId == null) { "团队组的管理员由团队定义，不能单独移交" }
        require(current.adminBotId != newAdminBotId) { "该成员已是本群管理员" }
        require(teamMembers(groupId).any { it.botId == newAdminBotId }) { "新管理员必须是本群成员" }
        publishTemplateGroup(current.copy(adminBotId = newAdminBotId))
    }

    @Transaction suspend fun createReferencedGroup(group: GroupChatEntity, teamId: String) {
        require(templateGroup(group.id) == null) { "群聊已存在" }
        publishTemplateGroup(group.copy(teamGroupId = null, teamVersion = null))
        syncDefinitionToChat(group.id, teamId)
    }

    @Transaction
    suspend fun installTeamTemplate(group: GroupChatEntity, members: List<String>, supervisors: Map<String, String>,
        policy: GroupGovernance, createNew: Boolean) {
        val existing = templateGroup(group.id)
        require(if (createNew) existing == null else existing?.adminBotId == group.adminBotId) { "群聊状态已变化" }
        if (createNew) publishTemplateGroup(group)
        val oldMembers = teamMembers(group.id)
        members.forEachIndexed { index, id -> insertTemplateMember(GroupMemberEntity(group.id, id, oldMembers.size + index, System.currentTimeMillis())) }
        val previous = effectiveTeam(group.id)
        val definition = top.tianyan.app.core.model.bot.BotTeamDefinition(group.adminBotId, previous.supervisors + supervisors,
            GroupGovernance(governance(group.id).rules + policy.rules))
        val teamId = linkedTeamId(group.id) ?: java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        saveDefinition(BotMemberGroupEntity(teamId, definitionGroup(teamId)?.name ?: group.name, now,
            kotlinx.serialization.json.Json.encodeToString(definition), draftRevision = definitionGroup(teamId)?.draftRevision ?: 0), teamMembers(group.id).map {
                BotMemberGroupMemberEntity(teamId, it.botId, it.displayOrder, it.joinedAt)
            })
        publishDefinition(teamId, requireNotNull(definitionGroup(teamId)).draftRevision, emptySet())
        syncDefinitionToChat(group.id, teamId)
    }
}
