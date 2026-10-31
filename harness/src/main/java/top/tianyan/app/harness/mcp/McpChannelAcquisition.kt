package top.tianyan.app.harness.mcp

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import top.tianyan.app.runtime.environment.ExecutionEnvironment
import top.tianyan.app.runtime.shell.LinuxSession
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Exists before startup; failed rollback stays reachable through the factory and session owner. */
internal class McpChannelAcquisition(
    val serverId: String,
    private val creation: Job,
    private val forget: (McpChannelAcquisition) -> Unit,
) {
    @Volatile var closing = false
    var environment: ExecutionEnvironment? = null
    var session: LinuxSession? = null
    var channel: LinuxMcpStdioChannel? = null
    var ownership: AutoCloseable? = null
    private val cleanup = McpResourceCleanup()
    private val sessionCleanup = McpResourceCleanup()
    private val environmentCleanup = McpResourceCleanup()
    private val detachLock = Any()
    private var detached = false

    fun detached() = synchronized(detachLock) {
        if (!detached) {
            ownership?.close()
            forget(this)
            detached = true
        }
    }

    suspend fun close(drainCreation: Boolean = true) {
        if (drainCreation) check(currentCoroutineContext()[ActiveMcpAcquisition]?.record !== this) {
            "Cannot close MCP from its own creation"
        }
        withContext(NonCancellable) {
            closing = true
            // Creator rollback uses the cleanup mutex too; join before taking it.
            if (drainCreation && !creation.isCompleted) creation.cancelAndJoin()
            cleanup.close {
                val published = channel
                if (published != null) published.close()
                else closeMcpActions(listOf(
                    { sessionCleanup.close { session?.close() } },
                    { environmentCleanup.close { environment?.close() } },
                ))
                detached()
            }
        }
    }
}

internal class ActiveMcpAcquisition(val record: McpChannelAcquisition) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveMcpAcquisition>
}
