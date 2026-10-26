package top.tianyan.app.runtime.shell

import top.tianyan.app.core.model.StorageMountBinding
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.bridge.SandboxCredentialStore
import top.tianyan.app.runtime.backend.BackendSelector
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    suspend fun stop(id: String): Boolean
    suspend fun stopInstance(process: ManagedProcess): Boolean = false
    suspend fun stopAll()
    suspend fun cleanupDeadProcesses(): Int
    fun list(): List<ManagedProcess>
    fun observeLogs(idOrToolId: String): Flow<List<String>>
    fun getLogs(idOrToolId: String): List<String>
    fun clearLogs(idOrToolId: String)
}

internal fun interface RegistrySessionFactory {
    suspend fun open(command: ShellCommand, distroId: String?, mounts: List<StorageMountBinding>): LinuxSession
}

class ProcessCleanupException(val failures: List<Throwable>) :
    IllegalStateException("${failures.size} 项进程资源清理失败", failures.firstOrNull()) {
    init { failures.drop(1).forEach(::addSuppressed) }
}

class ProcessRegistryImpl internal constructor(
    private val sessionFactory: RegistrySessionFactory,
    private val scope: CoroutineScope,
) : ProcessRegistry {
    constructor(
        pathManager: RuntimePathManager,
        backendSelector: BackendSelector,
        sandboxCredentials: SandboxCredentialStore? = null,
    ) : this(
        RegistrySessionFactory { command, distroId, mounts ->
            val safeDistro = distroId?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
                ?: pathManager.listInstalledDistroIds().firstOrNull() ?: "ubuntu"
            ProcessLinuxSession(
                backendSelector.active().build(
                    prootBinary = pathManager.activeProotFile(),
                    rootfsDir = pathManager.rootfsDir(safeDistro),
                    workspaceDir = pathManager.workspaceDir,
                    homeDir = pathManager.homeDir(safeDistro),
                    optDir = pathManager.tianyanRootDir(safeDistro),
                    tmpDir = pathManager.tmpDir,
                    attachmentsDir = pathManager.attachmentsDir,
                    command = command,
                    mounts = mounts,
                    hostCredential = sandboxCredentials?.environment(safeDistro).orEmpty(),
                ),
                hostEnvironment = pathManager.hostProcessEnvironment(safeDistro),
            )
        },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private class Entry(val id: String) {
        @Volatile var session: LinuxSession? = null
        @Volatile var process: ManagedProcess? = null
        var reader: Job? = null
        var sessionClosed = false
    }
    private val mutex = Mutex()
    private val processes = ConcurrentHashMap<String, Entry>()
    private val logs = ProcessLogStore()

    init {
        scope.launch {
            while (true) {
                delay(60_000)
                logs.prune()
            }
        }
    }

    override suspend fun start(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
        mounts: List<StorageMountBinding>,
    ): ManagedProcess = mutex.withLock {
        processes[id]?.let { release(it) }
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        val entry = Entry(id)
        processes[id] = entry
        try {
            entry.session = sessionFactory.open(command, distroId, mounts)
            val session = checkNotNull(entry.session)
            entry.process = ManagedProcess(id, System.currentTimeMillis(), session, toolId, type = type)
            currentCoroutineContext().ensureActive()
            entry.reader = readLogs(session, setOf(toolId ?: id, id))
            currentCoroutineContext().ensureActive()
            checkNotNull(entry.process)
        } catch (failure: Throwable) {
            try { release(entry) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun readLogs(session: LinuxSession, logKeys: Set<String>): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            logKeys.forEach { key ->
                logs.retain(key)
                logs.append(key, "[Tianyan] 正在启动服务进程...")
            }
            try {
                session.output.collect { terminalOutput ->
                    terminalOutput.text.lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            logKeys.forEach { logs.append(it, line) }
                        }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
            } finally {
                val exitNotice = "[Tianyan] 服务进程已停止"
                logKeys.forEach { key ->
                    logs.append(key, exitNotice)
                    logs.release(key)
                }
            }
        }

    override suspend fun stop(id: String): Boolean {
        val target = processes[id]
        return mutex.withLock {
            if (target == null || processes[id] !== target) return@withLock false
            release(target)
            true
        }
    }

    override suspend fun stopInstance(process: ManagedProcess): Boolean = mutex.withLock {
        val target = processes[process.id]
        if (target?.process !== process) return@withLock !process.session.isAlive
        release(target)
        true
    }

    override suspend fun stopAll() = mutex.withLock {
        releaseAll(processes.values.toList())
    }

    override suspend fun cleanupDeadProcesses(): Int = mutex.withLock {
        val dead = processes.values.filter { it.session?.isAlive != true }
        releaseAll(dead)
        dead.size
    }

    private suspend fun releaseAll(entries: List<Entry>) = withContext(NonCancellable) {
        val failures = mutableListOf<Throwable>()
        entries.forEach { entry ->
            try { release(entry) } catch (failure: Throwable) { failures += failure }
        }
        if (failures.isNotEmpty()) throw ProcessCleanupException(failures)
    }

    // Caller holds mutex; retain the exact generation until every owned resource is released.
    private suspend fun release(entry: Entry) = withContext(NonCancellable) {
        val failures = mutableListOf<Throwable>()
        try { entry.reader?.cancelAndJoin() } catch (failure: Throwable) { failures += failure }
        if (!entry.sessionClosed) {
            try {
                entry.session?.close()
                entry.sessionClosed = true
            } catch (failure: Throwable) { failures += failure }
        }
        if (failures.isNotEmpty()) throw ProcessCleanupException(failures)
        processes.remove(entry.id, entry)
        Unit
    }

    override fun list(): List<ManagedProcess> = processes.values.mapNotNull { it.process }.sortedBy { it.startedAt }

    override fun observeLogs(idOrToolId: String): Flow<List<String>> =
        logs.observe(idOrToolId)

    override fun getLogs(idOrToolId: String): List<String> =
        logs.get(idOrToolId)

    override fun clearLogs(idOrToolId: String) {
        logs.clear(idOrToolId)
    }
}
