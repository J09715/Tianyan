package top.tianyan.app.runtime.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.model.ExecutionMode
import top.tianyan.app.runtime.droidspaces.DroidspacesCommandBuilder
import top.tianyan.app.runtime.proot.ProotCommandBuilder

/**
 * 按当前 [ExecutionMode] 解析出应当使用的客态命令后端。
 *
 * **为什么是 `suspend`：** `effectiveExecutionMode` 是 DataStore 的冷 Flow，只有在挂起
 * 上下文里取 `first()` 才能拿到**当前真值**。此前用 `launchIn` 异步维护快照有两个缺陷：
 *
 * 1. 冷启动后第一批命令（健康检查、长驻会话）在首帧到达前会落到 PRoot；
 * 2. 运行中切换模式时，同一个 runtime 内会出现新旧后端混用。
 *
 * 改为由调用点在它自己的挂起路径里解析一次之后：
 * - 单条命令与单个会话在**整个生命周期内固定使用同一个后端**（argv 只构造一次）；
 * - 模式切换对下一条命令即时生效，不再有异步窗口。
 *
 * 实现保持无状态、无自带 scope：不持有 `CoroutineScope`，也就没有「scope 不会被取消」
 * 的问题，且可用 `flowOf(...)` 在 JVM 单测里确定性验证。
 */
fun interface BackendSelector {
    /** 解析出当前应使用的后端。每次调用都读取最新模式。 */
    suspend fun active(): GuestCommandBuilder
}

/**
 * 生产实现：**只有 [ExecutionMode.ROOT] 与实验开关同时成立**才走容器后端，
 * 其余一切情况（含 ROOT 但开关关闭）仍走 PRoot。
 *
 * 默认关是刻意的，也是 ADR-S001「PRoot 保持唯一沙箱后端」的落地方式：
 * 现有 ROOT 用户在开关打开前行为完全不变。`SHIZUKU` 只提权宿主自动化、
 * 不改变 Linux 运行方式；未知模式一律按 PRoot 处理，与 [ExecutionMode.fromId]
 * 的回落语义保持一致。
 *
 * 注意：该开关是**实验性、用户可见**的产品开关，用于控制功能可用性；
 * 它不是安全边界，详见 `docs/ADR_SANDBOX_BACKEND.md` 的威胁模型一节。
 */
class ModeBackendSelector(
    private val proot: ProotCommandBuilder,
    private val droidspaces: DroidspacesCommandBuilder,
    private val modes: Flow<ExecutionMode>,
    private val containerBackend: Flow<Boolean>,
) : BackendSelector {

    override suspend fun active(): GuestCommandBuilder =
        if (modes.first() == ExecutionMode.ROOT && containerBackend.first()) droidspaces else proot
}
