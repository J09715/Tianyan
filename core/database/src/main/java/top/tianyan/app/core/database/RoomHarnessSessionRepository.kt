package top.tianyan.app.core.database

class RoomHarnessSessionRepository(private val dao: HarnessSessionDao) : HarnessSessionRepository {
    override suspend fun bindExecutionTarget(id: String, target: String) = dao.bindExecutionTarget(id, target)
    override fun observeAll() = dao.observeAll()
    override suspend fun findById(id: String) = dao.findById(id)
    override suspend fun upsert(session: HarnessSessionEntity) = dao.upsert(session)
    override suspend fun touch(id: String, updatedAt: Long) = dao.touch(id, updatedAt)
    override suspend fun rename(id: String, title: String, updatedAt: Long) = dao.rename(id, title, updatedAt)
    override suspend fun setApprovalMode(id: String, approvalMode: String, updatedAt: Long) = dao.setApprovalMode(id, approvalMode, updatedAt)
    override suspend fun setApprovalModeForAll(approvalMode: String, updatedAt: Long) = dao.setApprovalModeForAll(approvalMode, updatedAt)
    override suspend fun setRunMode(id: String, runMode: String, updatedAt: Long) = dao.setRunMode(id, runMode, updatedAt)
    override suspend fun setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long) =
        dao.setModelSelection(id, modelId, modelVariant, updatedAt)


    override suspend fun deleteSession(id: String) = dao.deleteSession(id)
    override suspend fun countInRange(start: Long?, end: Long?) = dao.countInRange(start, end)
    override suspend fun listAll() = dao.listAll()
}
