package top.tianyan.app.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 仅记录本进程已完成的升级；按实际本地发行版隔离，不代表安装状态。 */
object McpCatalogRevisions {
    data class Key(val distribution: String, val server: String)
    private val state = MutableStateFlow<Map<Key, Long>>(emptyMap())
    val changes = state.asStateFlow()
    fun current(distribution: String, server: String): Long = state.value[Key(distribution, server)] ?: 0L
    fun upgraded(distribution: String, server: String) {
        val key = Key(distribution, server)
        state.update { it + (key to ((it[key] ?: 0L) + 1L)) }
    }
}
