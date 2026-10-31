package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.model.BtwQuery
import top.tianyan.app.core.model.BtwStatus

/** 旁路问答记录；答案不进入 harness 会话树，只做用户可见的本地卡片。 */
@Entity(tableName = "btw_queries", indices = [Index("sessionId")])
data class BtwQueryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val question: String,
    val answer: String = "",
    val status: String = "RUNNING",
    val error: String = "",
    val createdAt: Long,
    val finishedAt: Long? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
)

internal fun BtwQueryEntity.toModel(): BtwQuery = BtwQuery(
    id = id,
    sessionId = sessionId,
    question = question,
    answer = answer,
    status = runCatching { BtwStatus.valueOf(status) }.getOrDefault(BtwStatus.FAILED),
    error = error,
    createdAt = createdAt,
    finishedAt = finishedAt,
    modelId = modelId,
    providerId = providerId,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedTokens = cachedTokens,
)

internal fun BtwQuery.toEntity(): BtwQueryEntity = BtwQueryEntity(
    id = id,
    sessionId = sessionId,
    question = question,
    answer = answer,
    status = status.name,
    error = error,
    createdAt = createdAt,
    finishedAt = finishedAt,
    modelId = modelId,
    providerId = providerId,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedTokens = cachedTokens,
)

@Dao
interface BtwQueryDao {
    @Query("SELECT * FROM btw_queries WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    fun observeForSession(sessionId: String, limit: Int): Flow<List<BtwQueryEntity>>

    @Query("SELECT * FROM btw_queries WHERE id = :id")
    suspend fun byId(id: String): BtwQueryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: BtwQueryEntity)
}

class RoomBtwQueryRepository(private val dao: BtwQueryDao) : top.tianyan.app.core.database.repository.BtwQueryRepository {
    override fun observeForSession(sessionId: String, limit: Int): Flow<List<BtwQuery>> =
        dao.observeForSession(sessionId, limit).map { rows -> rows.map { it.toModel() } }

    override suspend fun save(query: BtwQuery) = dao.put(query.toEntity())

    override suspend fun get(id: String): BtwQuery? = dao.byId(id)?.toModel()
}
