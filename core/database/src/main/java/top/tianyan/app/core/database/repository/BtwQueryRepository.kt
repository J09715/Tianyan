package top.tianyan.app.core.database.repository

import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.model.BtwQuery

/** 旁路问答记录的读写：卡片用户可见、模型不可见，随会话查询。 */
interface BtwQueryRepository {
    fun observeForSession(sessionId: String, limit: Int = 20): Flow<List<BtwQuery>>

    suspend fun save(query: BtwQuery)

    suspend fun get(id: String): BtwQuery?
}
