package top.tianyan.app.runtime.terminal

import com.termux.terminal.TerminalSession
import top.tianyan.app.core.database.TerminalSessionEntity
import top.tianyan.app.core.database.TerminalSessionRepository
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.checkRuntimeDisposal
import top.tianyan.app.runtime.withRuntimeDisposal
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.environment.ExecutionEnvironmentFactory
import top.tianyan.app.runtime.environment.LocalTerminalLaunch
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TerminalSessionHandle internal constructor(
    val id: String,
    val label: String,
    val workingDirectory: String,
    val distributionId: String = "ubuntu",
    internal val process: TerminalProcess,
    /** 人类可读的环境标识（远程=主机名）；本地终端为 null。 */
    val environmentLabel: String? = null,
) {
    val termuxSession: TerminalSession get() = process.session
    val isAlive: Boolean get() = process.isAlive
}

data class TerminalRestoreFailure(val id: String, val label: String, val message: String)

/** Multi-session Termux console; creation, rollback and disposal retain explicit resource ownership. */
class TerminalSessionManager(
    private val linuxRuntime: LinuxRuntime,
    private val terminalSessionDao: TerminalSessionRepository,
    private val sessionClientRouter: TerminalSessionClientRouter,
    private val environmentFactory: ExecutionEnvironmentFactory? = null,
    private val processFactory: TerminalProcessFactory = TermuxTerminalProcessFactory(),
    private val remoteProcessFactory: RemoteTerminalProcessFactory = TermuxRemoteTerminalProcessFactory(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val resources = mutableMapOf<String, TerminalOwnedResources>()
    private val closeAllMutex = Mutex()
    private val restoreMutex = Mutex()
    private var closingAll = false
    private var generation = 0L
    private var fallbackPending = false
    private val _handles = MutableStateFlow<List<TerminalSessionHandle>>(emptyList())
    val handles: StateFlow<List<TerminalSessionHandle>> = _handles.asStateFlow()
    private val _restoreFailures = MutableStateFlow<List<TerminalRestoreFailure>>(emptyList())
    val restoreFailures: StateFlow<List<TerminalRestoreFailure>> = _restoreFailures.asStateFlow()
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    val activeHandle: StateFlow<TerminalSessionHandle?> =
        combine(_handles, _activeId) { handles, id -> handles.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)
    @Volatile private var restoredOnce = false

    suspend fun ensureActive(initialWorkingDirectory: String = DEFAULT_CWD) = restoreMutex.withLock { ensureActiveInternal(initialWorkingDirectory) }
    private suspend fun ensureActiveInternal(initialWorkingDirectory: String) {
        val failures = mutableListOf<Throwable>()
        _handles.value.filterNot { it.isAlive }.forEach { dead ->
            val owned = synchronized(stateLock) { resources[dead.id] } ?: return@forEach
            try { release(owned, deleteRow = false) } catch (t: Throwable) { failures += t }
        }
        if (failures.isNotEmpty()) throw TerminalCleanupException(failures)
        if (_handles.value.isEmpty()) restoredOnce = false
        if (!restoredOnce) {
            try {
                val rows = terminalSessionDao.listAll()
                _restoreFailures.value = emptyList()
                for (row in rows) {
                    if (_handles.value.any { it.id == row.id }) continue
                    try {
                        synchronized(stateLock) { resources[row.id] }?.takeIf { it.failedCreation }?.let { release(it, deleteRow = false) }
                        check(!row.distributionId.startsWith("ssh:") || row.executionTargetJson != null) { "旧终端未记录主机身份，请移除记录后新建" }
                        createSession(row.label, row.workingDirectory, row.distributionId, row.id)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        _restoreFailures.update { it + TerminalRestoreFailure(row.id, row.label, e.message?.take(180) ?: "恢复失败") }
                        // Unconfirmed disposal must remain blocking and reachable for retry.
                        if (synchronized(stateLock) { resources.containsKey(row.id) }) throw e
                    }
                }
                restoredOnce = true
                if (_handles.value.isNotEmpty()) {
                    _activeId.value = _handles.value.firstOrNull()?.id ?: _activeId.value
                    return
                }
            } catch (t: Throwable) { restoredOnce = false; throw t }
        }
        if (_handles.value.isEmpty()) createSession(workingDirectory = initialWorkingDirectory)
    }

    suspend fun createSession(
        label: String = "会话 ${_handles.value.size + 1}",
        workingDirectory: String = DEFAULT_CWD,
        distributionId: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): TerminalSessionHandle = coroutineScope {
        val capturedTarget = environmentFactory?.capture("", distributionId)
        val owned = TerminalOwnedResources(id, currentCoroutineContext().job)
        synchronized(stateLock) {
            check(!closingAll) { "Terminal sessions are closing" }
            require(id !in resources) { "Terminal session already exists or awaits cleanup: $id" }
            resources[id] = owned
        }
        try {
            val previous = terminalSessionDao.listAll().firstOrNull { it.id == id }
            check(previous?.distributionId?.startsWith("ssh:") != true || previous.executionTargetJson != null) {
                "旧远程终端未记录主机身份，请关闭后新建终端"
            }
            val targetJson = previous?.executionTargetJson ?: capturedTarget
            if (targetJson != null) environmentFactory?.restore("terminal:$id", "", targetJson)
            owned.environment = environmentFactory?.open("terminal:$id", "", distributionId)
            checkCreation(owned)
            val environment = owned.environment
            val targetDistro = environment?.id?.distribution ?: distributionId ?: linuxRuntime.activeDistroId.value
            val cwd = if (environment != null && environment !is LocalTerminalLaunch)
                environment.workingDirectory(workingDirectory.takeUnless { it == DEFAULT_CWD }) else workingDirectory
            val storedDirectory = if (workingDirectory == DEFAULT_CWD) cwd else workingDirectory
            val config = SessionConfig(workingDirectory = cwd, showBanner = true)
            checkCreation(owned)
            owned.process = when (environment) {
                null -> processFactory.create(linuxRuntime.buildInteractiveLaunch(config, targetDistro), sessionClientRouter)
                is LocalTerminalLaunch -> processFactory.create(environment.terminalLaunch(config), sessionClientRouter)
                else -> remoteProcessFactory.create(
                    LinuxSessionTerminalBackend(environment.openTerminal(config), scope), label, sessionClientRouter)
            }
            checkCreation(owned)
            owned.rollback = {
                if (previous == null) terminalSessionDao.delete(id) else terminalSessionDao.upsert(previous)
            }
            terminalSessionDao.upsert(TerminalSessionEntity(id = id, label = label, workingDirectory = storedDirectory,
                distributionId = targetDistro, createdAt = System.currentTimeMillis(), sortOrder = terminalSessionDao.nextOrder(),
                executionTargetJson = targetJson))
            checkCreation(owned)
            val handle = TerminalSessionHandle(id, label, storedDirectory, targetDistro, owned.process!!,
                environment?.takeUnless { it is LocalTerminalLaunch }?.label)
            synchronized(stateLock) {
                check(!owned.closing && !closingAll && resources[id] === owned) { "Terminal session was disposed during creation" }
                _handles.update { it + handle }
                _activeId.value = id
                owned.published = true
                owned.rollback = null
            }
            handle
        } catch (t: Throwable) {
            owned.failedCreation = true
            try { owned.release(drainCreation = false); forget(owned) }
            catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
            throw t
        }
    }

    private suspend fun checkCreation(owned: TerminalOwnedResources) {
        currentCoroutineContext().ensureActive()
        check(!owned.closing) { "Terminal session was disposed during creation" }
    }

    suspend fun openOrSwitchToProject(project: String, workingDirectory: String, distributionId: String? = null): TerminalSessionHandle {
        ensureActive()
        val existing = _handles.value.firstOrNull { it.workingDirectory == workingDirectory &&
            (distributionId == null || it.distributionId == distributionId) }
        if (existing != null) { _activeId.value = existing.id; return existing }
        return createSession(project.ifBlank { "工作区" }, workingDirectory, distributionId)
    }

    fun switchTo(id: String) {
        if (_handles.value.any { it.id == id }) _activeId.value = id
    }

    suspend fun removeFailedRestoration(id: String) = restoreMutex.withLock {
        require(_restoreFailures.value.any { it.id == id } && _handles.value.none { it.id == id }) { "此记录不是恢复失败的终端" }
        closeSession(id)
        terminalSessionDao.delete(id)
        _restoreFailures.update { failures -> failures.filterNot { it.id == id } }
    }

    suspend fun retryFailedRestorations() = restoreMutex.withLock {
        restoredOnce = false
        ensureActiveInternal(DEFAULT_CWD)
    }

    suspend fun closeSession(id: String) {
        val (owned, version) = synchronized(stateLock) { resources[id] to generation }
        if (owned == null) return
        checkRuntimeDisposal(owned)
        checkRuntimeDisposal(owned.disposalMutex)
        check(!owned.owns(currentCoroutineContext().job)) { "Cannot close terminal from its own creation" }
        release(owned, deleteRow = true)
        val fallback = synchronized(stateLock) {
            (owned.published && _handles.value.isEmpty() && !closingAll && generation == version && !fallbackPending)
                .also { if (it) fallbackPending = true }
        }
        if (fallback) try { createSession(label = "主终端", workingDirectory = DEFAULT_CWD) }
        finally { synchronized(stateLock) { fallbackPending = false } }
    }

    private suspend fun release(owned: TerminalOwnedResources, deleteRow: Boolean) = withRuntimeDisposal(owned.disposalMutex) {
        owned.disposalMutex.withLock {
            if (synchronized(stateLock) { resources[owned.id] !== owned }) return@withLock
            owned.release()
            if (deleteRow && !owned.failedCreation) terminalSessionDao.delete(owned.id)
            forget(owned)
        }
    }

    private fun forget(owned: TerminalOwnedResources) = synchronized(stateLock) {
        if (resources[owned.id] === owned) {
            resources.remove(owned.id)
            _handles.update { handles -> handles.filterNot { it.id == owned.id } }
            if (_activeId.value == owned.id) _activeId.value = _handles.value.lastOrNull()?.id
        }
    }

    suspend fun closeAllSessions() {
        synchronized(stateLock) { resources.values.toList() }.forEach {
            checkRuntimeDisposal(it); checkRuntimeDisposal(it.disposalMutex)
        }
        val caller = currentCoroutineContext().job
        synchronized(stateLock) {
            check(resources.values.none { it.owns(caller) }) { "Cannot close terminals from their own creation" }
        }
        withContext(NonCancellable) { closeAllMutex.withLock {
            val snapshot = synchronized(stateLock) {
                closingAll = true; generation++
                resources.values.toList().also { all -> all.forEach { it.closing = true; it.creation.cancel() } }
            }
            try {
                val failures = mutableListOf<Throwable>()
                for (owned in snapshot) {
                    try { release(owned, deleteRow = true) } catch (t: Throwable) { failures += t }
                }
                if (failures.isNotEmpty()) throw TerminalCleanupException(failures)
                terminalSessionDao.deleteAll()
                _restoreFailures.value = emptyList()
                _handles.value = emptyList(); _activeId.value = null; restoredOnce = false
            } finally { synchronized(stateLock) { closingAll = false } }
        } }
    }

    fun write(id: String, data: ByteArray) {
        val owned = synchronized(stateLock) { resources[id] }
        if (owned?.closing != false) return
        _handles.value.firstOrNull { it.id == id }?.process?.write(data)
    }
    fun paste(id: String, text: String) = write(id, text.toByteArray(Charsets.UTF_8))
    fun interrupt(id: String) = write(id, byteArrayOf(3))

    private companion object { const val DEFAULT_CWD = "/root" }
}
