package top.tianyan.app.core.tools

import top.tianyan.app.core.database.RuntimeDao
import top.tianyan.app.core.database.RuntimeDependencyRefEntity
import top.tianyan.app.core.database.RuntimeEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Persistence boundary for shared runtimes and their tool references. */
@Singleton
class RuntimeRepository @Inject constructor(
    private val runtimeDao: RuntimeDao,
) {
    suspend fun findRuntime(id: String): RuntimeEntity? = runtimeDao.findRuntime(id)
    suspend fun listInstalledRuntimes(): List<RuntimeEntity> = runtimeDao.listInstalledRuntimes()
    suspend fun saveRuntime(runtime: RuntimeEntity) = runtimeDao.upsertRuntime(runtime)
    suspend fun addReference(toolId: String, runtimeId: String) =
        runtimeDao.addReference(RuntimeDependencyRefEntity(toolId, runtimeId))
    suspend fun removeReference(toolId: String, runtimeId: String) =
        runtimeDao.removeReference(toolId, runtimeId)
    suspend fun referenceCount(runtimeId: String): Int = runtimeDao.referenceCount(runtimeId)
    suspend fun deleteRuntime(runtimeId: String) = runtimeDao.deleteRuntime(runtimeId)
}
