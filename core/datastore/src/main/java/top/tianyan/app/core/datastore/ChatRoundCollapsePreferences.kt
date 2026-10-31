package top.tianyan.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 「历史轮次中间过程自动折叠」偏好。
 *
 * 单独成文件的原因：承载偏好的 SettingsDataStore.kt 已触及 architecture-policy.json
 * 的文件尺寸棘轮上限（基线只许下调、不许上涨），因此新增偏好不再继续堆进该文件，
 * 而是以 Context 扩展的形式独立存放，由 SettingsDataStore 转发给偏好门面。
 *
 * 语义：默认 false —— 保持既有的「自然单行流」呈现，行为与历史版本完全一致；
 * 用户主动开启后，仅对**已结束的历史轮次**生效，进行中的最后一轮始终摊开。
 */
internal val chatRoundCollapseKey = booleanPreferencesKey("chat_history_round_collapse")

/** 历史轮次中间过程自动折叠开关（缺省 false）。 */
internal val Context.chatRoundCollapsePreference: Flow<Boolean>
    get() = settingsDataStore.data.map { it[chatRoundCollapseKey] ?: false }

/** 写入历史轮次中间过程自动折叠开关。 */
internal suspend fun Context.setChatRoundCollapsePreference(value: Boolean) {
    settingsDataStore.edit { it[chatRoundCollapseKey] = value }
}
