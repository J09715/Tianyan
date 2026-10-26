package top.tianyan.app.core.database

class RoomTerminalSessionRepository(private val dao: TerminalSessionDao) : TerminalSessionRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun listAll() = dao.listAll()
    override suspend fun nextOrder() = dao.nextOrder()
    override suspend fun upsert(session: TerminalSessionEntity) = dao.upsert(session)
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun deleteAll() = dao.deleteAll()
}
