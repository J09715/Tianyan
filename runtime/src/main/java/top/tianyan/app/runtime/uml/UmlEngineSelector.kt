package top.tianyan.app.runtime.uml

import top.tianyan.app.core.datastore.RuntimePreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 决定一次命令/会话用哪个引擎跑：默认 PRoot（兼容所有已装 RootFS），
 * 用户打开 UML 开关且本机探测通过时切到 UML（无仿真、快）。
 *
 * 选择是「每次解析」而非进程级切换：PRoot 与 UML 的 guest rootfs 路径不同，
 * 混用会让 /workspace 挂载错位，所以同一次会话必须锁定一个引擎。
 */
@Singleton
class UmlEngineSelector @Inject constructor(
    private val runtimePreferences: RuntimePreferences,
) {
    suspend fun activeEngine(): EngineType {
        val enabled = runtimePreferences.umlEngineEnabled.first()
        return if (enabled) EngineType.UML else EngineType.PROOT
    }

    suspend fun isUmlPreferred(): Boolean = activeEngine() == EngineType.UML
}
