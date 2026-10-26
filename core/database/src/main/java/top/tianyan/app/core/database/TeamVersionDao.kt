package top.tianyan.app.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.bot.*

@Entity(tableName = "team_versions", primaryKeys = ["teamId", "version"], foreignKeys = [ForeignKey(
    entity = BotMemberGroupEntity::class, parentColumns = ["id"], childColumns = ["teamId"], onDelete = ForeignKey.CASCADE)])
data class TeamVersionEntity(val teamId: String, val version: Int, val name: String,
    val memberIdsJson: String, val definitionJson: String, val publishedAt: Long) {
    fun model() = TeamPublishedVersion(teamId, version, name, Json.decodeFromString(memberIdsJson),
        Json.decodeFromString(definitionJson), publishedAt)
}

@Dao
interface TeamVersionDao : GroupTeamDao {
    @Query("SELECT * FROM team_versions")
    fun observeAllTeamVersions(): Flow<List<TeamVersionEntity>>
    @Insert suspend fun insertTeamVersion(version: TeamVersionEntity)
    @Query("SELECT * FROM team_versions WHERE teamId = :teamId AND version = :version")
    suspend fun teamVersion(teamId: String, version: Int): TeamVersionEntity?
    @Query("SELECT * FROM team_versions WHERE teamId = :teamId ORDER BY version DESC")
    fun observeVersions(teamId: String): Flow<List<TeamVersionEntity>>
    @Query("UPDATE bot_member_groups SET publishedVersion = :version WHERE id = :teamId")
    suspend fun setPublishedVersion(teamId: String, version: Int)
    @Query("SELECT COUNT(*) FROM bot_assignments WHERE groupId = :id AND status NOT IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun unfinishedTeamTasks(id: String): Int
    @Query("SELECT (SELECT COUNT(*) FROM group_deliveries WHERE groupId = :id AND status IN ('pending','submitting','admitted')) + " +
        "(SELECT COUNT(*) FROM group_rounds WHERE groupId = :id AND status = 'RUNNING') + " +
        "(SELECT COUNT(*) FROM group_late_replies WHERE groupId = :id AND status = 'WATCHING') + " +
        "(SELECT COUNT(*) FROM bot_work_batches WHERE groupId = :id AND status IN ('WAITING_CHILDREN','WAITING_TURN')) + " +
        "(SELECT COUNT(*) FROM harness_operations WHERE sessionId IN (SELECT sessionId FROM bot_group_sessions WHERE groupId = :id) AND status IN ('running','waiting_approval','suspended','aborting')) + " +
        "(SELECT COUNT(*) FROM harness_queue_items WHERE sessionId IN (SELECT sessionId FROM bot_group_sessions WHERE groupId = :id))")
    suspend fun pendingTeamWork(id: String): Int
    @Query("SELECT id AS groupId, name, teamVersion AS version, " +
        "(SELECT COUNT(*) FROM bot_assignments a WHERE a.groupId = g.id AND a.status NOT IN ('COMPLETED','FAILED','CANCELLED')) AS unfinishedTasks, " +
        "(SELECT COUNT(*) FROM group_deliveries d WHERE d.groupId = g.id AND d.status IN ('pending','submitting','admitted')) AS pendingWork " +
        "FROM group_chats g WHERE teamGroupId = :teamId ORDER BY createdAt")
    suspend fun initialTeamImpact(teamId: String): List<TeamUpgradeImpact>

    suspend fun teamImpact(teamId: String): List<TeamUpgradeImpact> = initialTeamImpact(teamId).map {
        it.copy(pendingWork = pendingTeamWork(it.groupId))
    }
}
