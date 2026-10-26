package top.tianyan.app.harness.mcp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.tianyan.app.harness.core.ResourceCleanupException
import top.tianyan.app.harness.core.withResourceDisposal

/** Concurrent disposal releases once; a failed release remains retryable. */
internal class McpResourceCleanup {
    private val mutex = Mutex()
    private var closed = false

    suspend fun close(release: suspend () -> Unit) = withResourceDisposal(this) {
        mutex.withLock {
            if (!closed) { release(); closed = true }
        }
    }
}

/** Server invalidation attempts every owner even when one subprocess cannot close. */
internal suspend fun closeMcpResources(ids: Collection<String>, close: suspend (String) -> Unit) =
    closeMcpActions(ids.map { id -> suspend { close(id) } })

internal suspend fun closeMcpActions(actions: Collection<suspend () -> Unit>) =
    withContext(NonCancellable) {
        val failures = mutableListOf<Throwable>()
        for (action in actions) {
            try { action() } catch (t: Throwable) {
                failures += t
            }
        }
        if (failures.isNotEmpty()) throw ResourceCleanupException(failures)
    }

/** Keep startup/registration failure as the primary diagnostic if disposal also fails. */
internal suspend fun rollbackMcpFailure(primary: Throwable, close: suspend () -> Unit): Nothing {
    withContext(NonCancellable) {
        try { close() } catch (cleanup: Throwable) { primary.addSuppressed(cleanup) }
    }
    throw primary
}
