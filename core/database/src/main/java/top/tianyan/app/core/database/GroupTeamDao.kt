package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.model.bot.GroupTeam

/** Reads the referenced team definition; old unlinked rooms retain their compatibility path. */
@Dao
interface GroupTeamDao {
    @Query("SELECT teamGroupId FROM group_chats WHERE id = :groupId")
    suspend fun linkedTeamId(groupId: String): String?

    @Query("SELECT v.definitionJson FROM team_versions v JOIN group_chats g ON g.teamGroupId = v.teamId AND g.teamVersion = v.version WHERE g.id = :groupId")
    suspend fun linkedDefinition(groupId: String): String?

    @Query("SELECT v.definitionJson FROM group_chats g LEFT JOIN team_versions v ON g.teamGroupId = v.teamId AND g.teamVersion = v.version WHERE g.id = :groupId")
    fun observeLinkedDefinition(groupId: String): Flow<String?>

    @Query("SELECT teamVersion FROM group_chats WHERE id = :groupId")
    suspend fun linkedVersion(groupId: String): Int?
    suspend fun linkedBinding(groupId: String): String? = linkedTeamId(groupId)?.let { "$it@${linkedVersion(groupId)}" }

    suspend fun effectiveTeam(groupId: String): GroupTeam {
        val rows = teamMembers(groupId)
        if (linkedTeamId(groupId) != null) {
            val definition = kotlinx.serialization.json.Json.decodeFromString<top.tianyan.app.core.model.bot.BotTeamDefinition>(requireNotNull(linkedDefinition(groupId)) { "团队组定义缺失" })
            return definition.team(rows.map { it.botId })
        }
        return GroupTeam(requireNotNull(teamAdmin(groupId)), rows.map { it.botId }.toSet(), rows.mapNotNull { row -> row.supervisorBotId?.let { row.botId to it } }.toMap())
    }

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY displayOrder")
    fun observeTeamMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun teamMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT adminBotId FROM group_chats WHERE id = :groupId")
    suspend fun teamAdmin(groupId: String): String?

    @Query("UPDATE group_members SET supervisorBotId = :supervisorId WHERE groupId = :groupId AND botId = :botId")
    suspend fun updateSupervisor(groupId: String, botId: String, supervisorId: String?)

    @Query("UPDATE group_members SET supervisorBotId = NULL WHERE groupId = :groupId AND supervisorBotId = :botId")
    suspend fun detachReports(groupId: String, botId: String)

    @Transaction
    suspend fun saveTeam(groupId: String, supervisors: Map<String, String>) {
        require(linkedTeamId(groupId) == null) { "上下级关系请在团队组中修改" }
        val admin = requireNotNull(teamAdmin(groupId)) { "群聊不存在" }
        val rows = teamMembers(groupId)
        GroupTeam(admin, rows.map { it.botId }.toSet(), supervisors).validate()
        rows.forEach { updateSupervisor(groupId, it.botId, supervisors[it.botId]?.takeUnless { id -> id == admin }) }
    }
}

val MIGRATION_74_75 = object : Migration(74, 75) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE group_members ADD COLUMN supervisorBotId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE bot_assignments ADD COLUMN parentAssignmentId TEXT DEFAULT NULL")
    }
}
