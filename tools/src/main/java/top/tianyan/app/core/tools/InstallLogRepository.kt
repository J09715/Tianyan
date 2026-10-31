package top.tianyan.app.core.tools

import top.tianyan.app.core.database.InstallLogDao
import top.tianyan.app.core.database.InstallLogEntity
import kotlinx.coroutines.flow.Flow

/** Persistence boundary for redacted installation and verification logs. */
class InstallLogRepository(
    private val dao: InstallLogDao,
) {
    suspend fun insert(log: InstallLogEntity) = dao.insert(log)
    suspend fun deleteForTool(distroId: String, toolId: String) = dao.deleteForTool(distroId, toolId)
    fun observeForTool(distroId: String, toolId: String): Flow<List<InstallLogEntity>> = dao.observeForTool(distroId, toolId)
    suspend fun deleteByDistro(distroId: String) = dao.deleteByDistro(distroId)
}

