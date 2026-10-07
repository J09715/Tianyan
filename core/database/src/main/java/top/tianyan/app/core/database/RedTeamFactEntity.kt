package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Session-scoped red-team fact; payload is a bounded JSON document owned by runtime tools. */
@Entity(
    tableName = "red_team_facts",
    primaryKeys = ["sessionId", "id"],
    indices = [Index(value = ["sessionId", "kind"]), Index(value = ["sessionId", "updatedAt"])],
)
data class RedTeamFactEntity(
    val sessionId: String,
    val id: String,
    val kind: String,
    val title: String,
    val target: String? = null,
    val severity: String? = null,
    val status: String = "observed",
    val payload: String = "{}",
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface RedTeamFactDao {
    @Query("SELECT * FROM red_team_facts WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    fun observeForSession(sessionId: String): Flow<List<RedTeamFactEntity>>

    @Query("SELECT * FROM red_team_facts WHERE sessionId = :sessionId AND kind = :kind ORDER BY updatedAt DESC")
    fun observeKind(sessionId: String, kind: String): Flow<List<RedTeamFactEntity>>

    @Query("SELECT * FROM red_team_facts WHERE sessionId = :sessionId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(sessionId: String, limit: Int): List<RedTeamFactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: RedTeamFactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(facts: List<RedTeamFactEntity>)

    @Query("DELETE FROM red_team_facts WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
