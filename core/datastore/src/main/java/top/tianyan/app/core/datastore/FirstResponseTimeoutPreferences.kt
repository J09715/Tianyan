package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.tianyan.app.core.model.FirstResponseTimeoutPolicy

/**
 * 「等待模型首个响应」的客户端超时（秒）。
 *
 * 单独成文件的原因与 [ChatRoundCollapsePreferences] 相同：SettingsDataStore.kt
 * 已在文件尺寸棘轮上限，新增偏好以 Context 扩展存放，再由门面转发。
 *
 * 缺省 90 秒。0 表示关闭这项上限。
 */
internal val firstResponseTimeoutSecondsKey = intPreferencesKey("agent_first_response_timeout_seconds")

internal val Context.firstResponseTimeoutSecondsPreference: Flow<Int>
    get() = settingsDataStore.data.map {
        FirstResponseTimeoutPolicy.normalizeSeconds(it[firstResponseTimeoutSecondsKey])
    }

internal suspend fun Context.setFirstResponseTimeoutSecondsPreference(value: Int) {
    settingsDataStore.edit {
        it[firstResponseTimeoutSecondsKey] = FirstResponseTimeoutPolicy.normalizeSeconds(value)
    }
}
