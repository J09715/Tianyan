package top.tianyan.app.core.database

import kotlinx.coroutines.flow.Flow

interface AiModelRepository {
    fun observeAll(): Flow<List<AiModelEntity>>
    suspend fun findById(id: String): AiModelEntity?
    suspend fun activeModel(): AiModelEntity?
    suspend fun upsert(model: AiModelEntity)
    suspend fun importBatch(expected: List<AiModelEntity>, models: List<AiModelEntity>)
    suspend fun clearActive()
    suspend fun setActive(id: String)
    suspend fun activate(id: String) // 唯一激活切换：clearActive + setActive 必须单事务（转发 AiModelDao.activate）
    suspend fun updateReasoning(id: String, mode: String?, effort: String?)
    suspend fun delete(id: String)
}

class RoomAiModelRepository(private val dao: AiModelDao) : AiModelRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun findById(id: String) = dao.findById(id)
    override suspend fun activeModel() = dao.activeModel()
    override suspend fun upsert(model: AiModelEntity) = dao.upsert(model)
    override suspend fun importBatch(expected: List<AiModelEntity>, models: List<AiModelEntity>) = dao.importBatch(expected, models)
    override suspend fun clearActive() = dao.clearActive()
    override suspend fun setActive(id: String) = dao.setActive(id)
    override suspend fun activate(id: String) = dao.activate(id)
    override suspend fun updateReasoning(id: String, mode: String?, effort: String?) = dao.updateReasoning(id, mode, effort)
    override suspend fun delete(id: String) = dao.delete(id)
}

