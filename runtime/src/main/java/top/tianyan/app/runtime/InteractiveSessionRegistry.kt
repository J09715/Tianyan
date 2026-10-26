package top.tianyan.app.runtime

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.ProcessCleanupException

/** A stopped process can still own a failed cleanup; retain it until close succeeds. */
internal class InteractiveSessionRegistry {
    private val sessions = ConcurrentHashMap<LinuxSession, String>()

    fun track(session: LinuxSession, distroId: String): LinuxSession {
        val tracked = object : LinuxSession by session {
            private val mutex = Mutex()
            private var released = false

            override suspend fun close() {
                val closing = currentCoroutineContext()[ClosingSessions]?.sessions.orEmpty()
                check(this !in closing) { "会话清理不能递归关闭同一注册实例" }
                val tracked = this
                withContext(NonCancellable + ClosingSessions(closing + this)) {
                    mutex.withLock {
                        if (!released) {
                            session.close()
                            released = true
                            sessions.remove(tracked)
                        }
                    }
                }
            }
        }
        sessions[tracked] = distroId
        return tracked
    }

    fun hasSessions(): Boolean = sessions.isNotEmpty()

    suspend fun closeAll(distroId: String? = null) {
        val targets = sessions.entries.filter { distroId == null || it.value == distroId }.map { it.key }
        closeRuntimeResources(targets.map { session -> suspend { session.close() } })
    }

    private class ClosingSessions(val sessions: Set<LinuxSession>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ClosingSessions>
    }
}

internal suspend fun closeRuntimeResources(actions: List<suspend () -> Unit>) = withContext(NonCancellable) {
    val failures = mutableListOf<Throwable>()
    actions.forEach { action ->
        try { action() } catch (failure: Throwable) { failures += failure }
    }
    if (failures.isNotEmpty()) throw ProcessCleanupException(failures)
}
