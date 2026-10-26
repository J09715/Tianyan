package top.tianyan.app.plugin.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull

/** One generation owns its jobs, resource ledger and in-flight page/call leases. */
class PluginActivation internal constructor(
    val pluginId: String,
    val generation: Long,
    context: CoroutineContext,
    onFailure: () -> Unit,
) {
    private val monitor = Any()
    private val job = SupervisorJob()
    private val failed = AtomicBoolean(false)
    internal val hasFailed get() = failed.get()
    val scope = CoroutineScope(context.minusKey(Job) + job + CoroutineExceptionHandler { _, _ ->
        failed.set(true)
        onFailure()
    })
    private val resources = linkedMapOf<String, suspend () -> Boolean>()
    private var accepting = true
    /** Synchronous admission hint for document bridges; stop closes this before UI disposal. */
    val isAccepting: Boolean get() = synchronized(monitor) { accepting && !failed.get() && job.isActive }
    private var leases = 0
    private val drained = CompletableDeferred<Unit>()

    /** Register immediately after acquisition. Release must be idempotent and return confirmed success. */
    fun own(id: String, release: suspend () -> Boolean) = synchronized(monitor) {
        check(accepting) { "Plugin generation is draining" }
        require(id.isNotBlank() && id !in resources) { "Invalid or duplicate resource identity" }
        resources[id] = release
    }

    /** Page disposal may return its resource before the entire generation stops. */
    suspend fun releaseOwned(id: String): Boolean {
        val release = synchronized(monitor) { resources[id] } ?: return true
        val confirmed = try { release() } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }
        if (!confirmed) return false
        synchronized(monitor) { if (resources[id] === release) resources.remove(id) }
        return true
    }

    internal fun acquire(): PluginLease? = synchronized(monitor) {
        if (!accepting) return null
        leases++
        PluginLease(this) {
            synchronized(monitor) {
                leases--
                if (!accepting && leases == 0) drained.complete(Unit)
            }
        }
    }

    internal fun stopAccepting() = synchronized(monitor) {
        accepting = false
        if (leases == 0) drained.complete(Unit)
    }

    /** A failed or timed-out close retains unconfirmed resources for explicit retry. */
    internal suspend fun close(timeoutMillis: Long): Boolean {
        stopAccepting()
        return withTimeoutOrNull(timeoutMillis) {
            drained.await()
            job.cancelAndJoin()
            while (true) {
                val entry = synchronized(monitor) { resources.entries.lastOrNull()?.let { it.key to it.value } }
                    ?: break
                val released = try { entry.second() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { false }
                if (!released) return@withTimeoutOrNull false
                synchronized(monitor) { resources.remove(entry.first) }
            }
            true
        } ?: false
    }
}

/** Idempotent close allows disposal and cancellation paths to converge safely. */
class PluginLease internal constructor(val activation: PluginActivation, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
