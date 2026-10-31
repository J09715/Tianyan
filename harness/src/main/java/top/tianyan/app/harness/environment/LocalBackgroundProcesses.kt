package top.tianyan.app.harness.environment

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.tianyan.app.harness.core.SessionResourceScope
import top.tianyan.app.runtime.environment.ExecutionCommands
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.ShellCommand

/** Each process generation owns one cleanup scope; stopped generations cannot stop a replacement. */
internal class LocalBackgroundProcesses(
    private val commands: ExecutionCommands,
    private val owner: SessionResourceScope,
) {
    private class Entry {
        val resources = SessionResourceScope()
        var process: ManagedProcess? = null
        var registration: AutoCloseable? = null
        var stopped = false
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun start(id: String, command: ShellCommand): ManagedProcess = owner.activity {
        locks.getOrPut(id) { Mutex() }.withLock {
            require(commands.listBackground().none { it.id == id && it.session.isAlive }) { "后台进程 $id 已在运行" }
            entries[id]?.let { release(id, it) }
            val entry = Entry()
            entry.resources.own { entry.process?.let { entry.stopped = commands.stopBackground(it.id) } }
            entries[id] = entry
            try {
                entry.registration = owner.own { release(id, entry) }
                entry.resources.activity {
                    entry.process = commands.startBackground(id, command)
                    currentCoroutineContext().ensureActive()
                }
                currentCoroutineContext().ensureActive()
                checkNotNull(entry.process)
            } catch (t: Throwable) {
                try { release(id, entry) } catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
                throw t
            }
        }
    }

    suspend fun stop(id: String): Boolean = owner.activity {
        val target = entries[id]
        locks.getOrPut(id) { Mutex() }.withLock {
            if (entries[id] !== target) return@withLock false
            val entry = target ?: return@withLock commands.stopBackground(id)
            release(id, entry)
            entry.stopped
        }
    }

    private suspend fun release(id: String, entry: Entry) = withContext(NonCancellable) {
        entry.resources.close()
        entry.registration?.close()
        entries.remove(id, entry)
    }
}
