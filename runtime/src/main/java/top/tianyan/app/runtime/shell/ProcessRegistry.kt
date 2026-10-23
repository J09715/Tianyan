package top.tianyan.app.runtime.shell

import top.tianyan.app.core.model.StorageMountBinding
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.proot.ProotCommandBuilder
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProcessType {
    TERMINAL,
    SERVICE,
    COMMAND,
    UNKNOWN,
}

data class ManagedProcess(
    val id: String,
    val startedAt: Long,
    val session: LinuxSession,
    val toolId: String? = null,
    val pid: Long? = session.pid,
    val type: ProcessType = ProcessType.UNKNOWN,
)

interface ProcessRegistry {
    suspend fun start(
        id: String,
        command: ShellCommand,
        toolId: String? = null,
        type: ProcessType = ProcessType.SERVICE,
        distroId: String? = null,
        mounts: List<StorageMountBinding> = emptyList(),
    ): ManagedProcess

    /** UML 引擎的后台进程：会话跑在 guest 里（exec channel，无 PTY）。 */
    suspend fun startUml(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        engine: top.tianyan.app.runtime.uml.UmlEngine,
    ): ManagedProcess

    suspend fun stop(id: String): Boolean
    suspend fun stopAll()
    suspend fun cleanupDeadProcesses(): Int
    fun list(): List<ManagedProcess>
    fun observeLogs(idOrToolId: String): Flow<List<String>>
    fun getLogs(idOrToolId: String): List<String>
    fun clearLogs(idOrToolId: String)
}

@Singleton
class ProcessRegistryImpl @Inject constructor(
    private val pathManager: RuntimePathManager,
    private val prootCommandBuilder: ProotCommandBuilder,
) : ProcessRegistry {
    private val mutex = Mutex()

    /**
     * 进程表。
     *
     * 用 [Collections.synchronizedMap] 而不是裸 [LinkedHashMap]：写路径都走
     * [mutex]，但 [list] 是**非挂起**函数——它的接口签名里没有 suspend，
     * 没法在 mutex.withLock 里读。
     *
     * 裸 LinkedHashMap 在这种「写端加锁、读端不加锁」的结构下不是线程安全的：
     * 读端遍历 values 时写端正好在扩容/重建节点，会抛
     * ConcurrentModificationException。症状是「后台任务列表偶尔刷新就崩」，
     * 且只在特定时序下出现，极难复现。
     *
     * synchronizedMap 保证每次操作（含遍历）都持锁，读端拿到的快照是自洽的。
     * 锁粒度是进程表本身，与 [mutex] 不冲突：mutex 保护的是「start/stop 的复合
     * 操作」，synchronizedMap 保护的是「单次读写」。
     */
    private val processes = Collections.synchronizedMap(LinkedHashMap<String, ManagedProcess>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logsMap = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()

    private fun getOrCreateLogFlow(key: String): MutableStateFlow<List<String>> =
        logsMap.computeIfAbsent(key) { MutableStateFlow(emptyList()) }

    private fun appendLog(key: String, line: String) {
        val flow = getOrCreateLogFlow(key)
        val current = flow.value
        val updated = if (current.size >= 500) {
            current.drop(current.size - 499) + line
        } else {
            current + line
        }
        flow.value = updated
    }

    override suspend fun start(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
        mounts: List<StorageMountBinding>,
    ): ManagedProcess = mutex.withLock {
        processes.remove(id)?.session?.close()
        val safeDistro = distroId?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
            ?: pathManager.listInstalledDistroIds().firstOrNull() ?: "ubuntu"
        val session = ProcessLinuxSession(
            prootCommandBuilder.build(
                prootBinary = pathManager.activeProotFile(),
                rootfsDir = pathManager.rootfsDir(safeDistro),
                workspaceDir = pathManager.workspaceDir,
                homeDir = pathManager.homeDir(safeDistro),
                optDir = pathManager.tianyanRootDir(safeDistro),
                tmpDir = pathManager.tmpDir,
                attachmentsDir = pathManager.attachmentsDir,
                command = command,
                mounts = mounts,
            ),
            hostEnvironment = pathManager.hostProcessEnvironment(safeDistro),
        )

        val logKey = toolId ?: id
        appendLog(logKey, "[Tianyan] 正在启动服务进程...")
        if (toolId != null && toolId != id) {
            appendLog(id, "[Tianyan] 正在启动服务进程...")
        }

        scope.launch {
            try {
                session.output.collect { terminalOutput ->
                    terminalOutput.text.lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            appendLog(logKey, line)
                            if (toolId != null && toolId != id) {
                                appendLog(id, line)
                            }
                        }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                // 输出流中断与进程正常退出在 UI 上表现一致（都只看到"已停止"），
                // 不记日志就无从区分是崩溃还是正常收尾。
                appendLog(logKey, "[Tianyan] 输出流中断：${e.message ?: e::class.simpleName}")
            } finally {
                val exitNotice = "[Tianyan] 服务进程已停止"
                appendLog(logKey, exitNotice)
                if (toolId != null && toolId != id) {
                    appendLog(id, exitNotice)
                }
            }
        }

        ManagedProcess(
            id = id,
            startedAt = System.currentTimeMillis(),
            session = session,
            toolId = toolId,
            pid = session.pid,
            type = type,
        ).also { processes[id] = it }
    }

    /**
     * UML 引擎的后台进程：会话在 guest 里跑（exec channel）。
     * 进程表、日志聚合、停止/清理与 PRoot 路径完全一致——调用方无需区分引擎。
     */
    override suspend fun startUml(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        engine: top.tianyan.app.runtime.uml.UmlEngine,
    ): ManagedProcess = mutex.withLock {
        processes.remove(id)?.session?.close()
        val session = engine.openExecSession(command)

        val logKey = toolId ?: id
        appendLog(logKey, "[Tianyan] 正在启动 UML 服务进程...")
        if (toolId != null && toolId != id) {
            appendLog(id, "[Tianyan] 正在启动 UML 服务进程...")
        }

        scope.launch {
            try {
                session.output.collect { terminalOutput ->
                    terminalOutput.text.lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            appendLog(logKey, line)
                            if (toolId != null && toolId != id) {
                                appendLog(id, line)
                            }
                        }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                appendLog(logKey, "[Tianyan] UML 输出流中断：${e.message ?: e::class.simpleName}")
            } finally {
                appendLog(logKey, "[Tianyan] UML 服务进程已停止")
                if (toolId != null && toolId != id) {
                    appendLog(id, "[Tianyan] UML 服务进程已停止")
                }
            }
        }

        ManagedProcess(
            id = id,
            startedAt = System.currentTimeMillis(),
            session = session,
            toolId = toolId,
            pid = session.pid,
            type = type,
        ).also { processes[id] = it }
    }

    override suspend fun stop(id: String): Boolean = mutex.withLock {
        val process = processes.remove(id) ?: return@withLock false
        process.session.close()
        true
    }

    override suspend fun stopAll() = mutex.withLock {
        // 先取快照再 close：close 可能阻塞（等进程退出），期间不持有进程表的锁，
        // 否则别处的 list() 会被拖住。close 之后 clear 一次即可。
        val snapshot = synchronized(processes) { processes.values.toList() }
        snapshot.forEach { it.session.close() }
        processes.clear()
    }

    override suspend fun cleanupDeadProcesses(): Int = mutex.withLock {
        // 同上：先取快照（此时持有 mutex，写端不会同时改），再逐个 remove。
        val dead = synchronized(processes) { processes.values.filter { !it.session.isAlive } }
        dead.forEach { processes.remove(it.id) }
        dead.size
    }

    /**
     * 进程快照。
     *
     * 显式在 [processes] 的监视器上同步取整份快照：返回值是一个**独立列表**，
     * 之后写端再改进程表也不会影响这份快照——调用方（UI 渲染、运行详情面板）
     * 拿到的必须是某个时刻的一致状态。
     */
    override fun list(): List<ManagedProcess> = synchronized(processes) { processes.values.toList() }

    override fun observeLogs(idOrToolId: String): Flow<List<String>> =
        getOrCreateLogFlow(idOrToolId).asStateFlow()

    override fun getLogs(idOrToolId: String): List<String> =
        getOrCreateLogFlow(idOrToolId).value

    override fun clearLogs(idOrToolId: String) {
        getOrCreateLogFlow(idOrToolId).value = emptyList()
    }
}
