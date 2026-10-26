package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 实验性容器后端（Droidspaces）开关，**默认关**。
 *
 * 关闭时 ROOT 模式仍走 PRoot，与 `ADR-S001：PRoot 保持唯一沙箱后端` 一致，
 * 现有 ROOT 用户行为完全不变；只有用户显式打开，ROOT 模式才会把整条命令链路
 * 切到容器后端。
 *
 * 说明：这是**产品可用性开关**，不是安全边界——详见
 * `docs/ADR_SANDBOX_BACKEND.md` 附录 A 的威胁模型（内含上游 SELinux 审计）。
 */
class ContainerBackendPreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[ENABLED] = value }
    }

    private companion object { val ENABLED = booleanPreferencesKey("container_backend_enabled") }
}
