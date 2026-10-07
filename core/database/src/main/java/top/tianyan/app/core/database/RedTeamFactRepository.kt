package top.tianyan.app.core.database

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface RedTeamFactRepository {
    fun observeForSession(sessionId: String): Flow<List<RedTeamFactEntity>>
    fun observeKind(sessionId: String, kind: String): Flow<List<RedTeamFactEntity>>
    suspend fun recent(sessionId: String, limit: Int = 100): List<RedTeamFactEntity>
    suspend fun upsert(fact: RedTeamFactEntity)
    suspend fun upsertAll(facts: List<RedTeamFactEntity>)
    suspend fun deleteForSession(sessionId: String)
}

@Singleton
class RoomRedTeamFactRepository @Inject constructor(
    private val dao: RedTeamFactDao,
) : RedTeamFactRepository {
    override fun observeForSession(sessionId: String) = dao.observeForSession(sessionId)
    override fun observeKind(sessionId: String, kind: String) = dao.observeKind(sessionId, kind)
    override suspend fun recent(sessionId: String, limit: Int) = dao.recent(sessionId, limit.coerceIn(1, 500))
    override suspend fun upsert(fact: RedTeamFactEntity) = dao.upsert(fact)
    override suspend fun upsertAll(facts: List<RedTeamFactEntity>) = dao.upsertAll(facts.take(500))
    override suspend fun deleteForSession(sessionId: String) = dao.deleteForSession(sessionId)
}
