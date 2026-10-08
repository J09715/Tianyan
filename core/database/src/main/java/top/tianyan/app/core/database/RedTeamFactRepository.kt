package top.tianyan.app.core.database

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface RedTeamFactRepository {
    fun observeForSession(sessionId: String): Flow<List<RedTeamFactEntity>>
    fun observeKind(sessionId: String, kind: String): Flow<List<RedTeamFactEntity>>
    suspend fun recent(sessionId: String, limit: Int = 100): List<RedTeamFactEntity>

    /**
     * 面板批量读：一次取回若干类型的事实。
     *
     * 默认实现退化成 [recent]，老测试替身不必实现新方法；Room 实现走 `byKinds`
     * 绕开 [recent] 的 500 条上限——上游面板默认就展示 400 台资产，
     * 再叠上端口/漏洞/得分事实，一次演练很容易越过 500，面板会显示成「记录变少了」。
     */
    suspend fun byKinds(sessionId: String, kinds: List<String>, limit: Int = 5000): List<RedTeamFactEntity> =
        recent(sessionId, limit).filter { it.kind in kinds }
    suspend fun upsert(fact: RedTeamFactEntity)
    suspend fun upsertAll(facts: List<RedTeamFactEntity>)
    suspend fun deleteForSession(sessionId: String)
    suspend fun deleteById(sessionId: String, id: String)
}

@Singleton
class RoomRedTeamFactRepository @Inject constructor(
    private val dao: RedTeamFactDao,
) : RedTeamFactRepository {
    override fun observeForSession(sessionId: String) = dao.observeForSession(sessionId)
    override fun observeKind(sessionId: String, kind: String) = dao.observeKind(sessionId, kind)
    override suspend fun recent(sessionId: String, limit: Int) = dao.recent(sessionId, limit.coerceIn(1, 500))
    override suspend fun byKinds(sessionId: String, kinds: List<String>, limit: Int) =
        if (kinds.isEmpty()) emptyList() else dao.byKinds(sessionId, kinds, limit.coerceIn(1, 20000))
    override suspend fun upsert(fact: RedTeamFactEntity) = dao.upsert(fact)
    override suspend fun upsertAll(facts: List<RedTeamFactEntity>) = dao.upsertAll(facts.take(500))
    override suspend fun deleteForSession(sessionId: String) = dao.deleteForSession(sessionId)
    override suspend fun deleteById(sessionId: String, id: String) = dao.deleteById(sessionId, id)
}
