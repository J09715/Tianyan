package top.tianyan.app.runtime.uml

import top.tianyan.app.core.datastore.RuntimePreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 决定一次命令/会话用哪个引擎跑。
 *
 * UML（完整 root Linux 系统：自带内核 + root，无仿真）是默认执行引擎；
 * 两种情况该会话自动回退 PRoot：
 * ① 用户在设置里显式切到 PRoot（opt-out，不是 opt-in）；
 * ② UML 产物未就位（guest 镜像未下载完 / 四件套缺失）——
 *    [UmlRootfsInstaller] 会在后台补齐镜像，就位后的下一个会话自动切回 UML。
 *
 * 判定只看文件存在性（非阻塞、无内核探测）：探测是设置页里的参考信息，
 * 不做主链路门槛；设备真跑不起内核时 UmlEngine.startBlocking 会快速失败并
 * 暴露原因（见 UmlProbe 的 BLOCKED 结论），该会话保持 PRoot 兜底。
 *
 * 选择是「每次解析」而非进程级切换：PRoot 与 UML 的 guest rootfs 路径不同，
 * 混用会让 /workspace 挂载错位，所以同一次会话必须锁定一个引擎。
 */
@Singleton
class UmlEngineSelector @Inject constructor(
    private val runtimePreferences: RuntimePreferences,
    private val layout: UmlRuntimeLayout,
) {
    /** 用户偏好：UML 默认开（带内核 + root 的完整 Linux 系统），可显式切回 PRoot。 */
    suspend fun preference(): EngineType {
        val enabled = runtimePreferences.umlEngineEnabled.first()
        return if (enabled) EngineType.UML else EngineType.PROOT
    }

    /** 实际生效引擎：偏好 UML 且产物齐 → UML，否则 PRoot。 */
    suspend fun activeEngine(): EngineType =
        resolve(preference(), layout.missing())

    suspend fun isUmlPreferred(): Boolean = activeEngine() == EngineType.UML

    /**
     * 纯决策逻辑（可单测）：偏好 UML 但产物缺失 → 该会话回退 PRoot；
     * 偏好 PRoot 或产物齐 → 按偏好走。
     */
    companion object {
        fun resolve(userPreference: EngineType, missingArtifacts: List<String>): EngineType {
            if (userPreference != EngineType.UML) return EngineType.PROOT
            return if (missingArtifacts.isEmpty()) EngineType.UML else EngineType.PROOT
        }
    }
}
