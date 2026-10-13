package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "tool_settings", primaryKeys = ["distroId", "toolId"])
data class ToolSettingsEntity(
    val distroId: String,
    val toolId: String,
    val autoStart: Boolean = false,
)

@Dao
interface ToolSettingsDao {
    @Query("SELECT autoStart FROM tool_settings WHERE distroId = :distroId AND toolId = :toolId LIMIT 1")
    fun observeAutoStart(distroId: String, toolId: String): Flow<Boolean?>

    /**
     * 需要开机自启的工具 id。
     *
     * 只返回显式打开开关的行：缺失行与 autoStart=false 的行语义相同，都不启。
     */
    @Query("SELECT toolId FROM tool_settings WHERE distroId = :distroId AND autoStart = 1")
    suspend fun listAutoStartToolIds(distroId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ToolSettingsEntity)

    @Query("DELETE FROM tool_settings WHERE distroId = :distroId AND toolId = :toolId")
    suspend fun delete(distroId: String, toolId: String)

    @Query("DELETE FROM tool_settings WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)
}

@Singleton
class ToolSettingsRepository @Inject constructor(
    private val dao: ToolSettingsDao,
) {
    fun autoStart(distroId: String, toolId: String): Flow<Boolean> = dao.observeAutoStart(distroId, toolId).map { it ?: false }

    /** 开机需要拉起的工具 id 列表；供启动流程消费，不再是只写不读的装饰字段。 */
    suspend fun listAutoStartToolIds(distroId: String): List<String> = dao.listAutoStartToolIds(distroId)

    suspend fun setAutoStart(distroId: String, toolId: String, enabled: Boolean) = dao.upsert(ToolSettingsEntity(distroId, toolId, enabled))
    suspend fun delete(distroId: String, toolId: String) = dao.delete(distroId, toolId)
    suspend fun deleteByDistro(distroId: String) = dao.deleteByDistro(distroId)
}
