package top.tianyan.app.runtime.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.LinuxRuntime

/** Load identity independently of RootFS startup; never treat the temporary Ubuntu value as a choice. */
class LocalDistributionSelection(
    persisted: Flow<String>, private val active: StateFlow<String>, private val runtimeState: StateFlow<RuntimeState>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(preferences: RuntimePreferences, runtime: LinuxRuntime) :
        this(preferences.selectedDistribution, runtime.activeDistroId, runtime.state)
    private val saved = persisted.stateIn(scope, SharingStarted.Eagerly, null)
    val changes: Flow<String> = combine(saved, active, runtimeState) { selection, live, state ->
        if (state is RuntimeState.Ready) live else selection
    }.filterNotNull().distinctUntilChanged()
    fun current(): String = if (runtimeState.value is RuntimeState.Ready) active.value
        else checkNotNull(saved.value) { "本地系统选择正在加载，请稍后重试" }
    suspend fun capture(): String {
        if (runtimeState.value is RuntimeState.Ready) return active.value
        val persisted = changes.first()
        return if (runtimeState.value is RuntimeState.Ready) active.value else persisted
    }
}
