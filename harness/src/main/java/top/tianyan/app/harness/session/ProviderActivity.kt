package top.tianyan.app.harness.session

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
data class ProviderActivity(
    val operationId: String,
    val requestStartedAt: Long,
    val attempt: Int,
    val phase: String = "waiting_model",
    val lastTransportAt: Long? = null,
    val lastOutputAt: Long? = null,
    val retryReason: String? = null,
)

/** Transport activity never updates output or execution progress. */
class ProviderActivityTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow<Map<String, ProviderActivity>>(emptyMap())
    private val pendingReasons = java.util.concurrent.ConcurrentHashMap<String, String>()
    val state = mutable.asStateFlow()
    fun begin(sessionId: String, operationId: String, attempt: Int) {
        val reason = pendingReasons.remove(sessionId)
        val started = clock()
        mutable.update { rows -> rows + (sessionId to ProviderActivity(operationId, started, attempt,
            retryReason = (reason ?: "provider_retry").takeIf { attempt > 1 })) }
    }
    fun transport(sessionId: String) = mutable.update { rows ->
        rows[sessionId]?.let { rows + (sessionId to it.copy(lastTransportAt = clock())) } ?: rows
    }
    fun output(sessionId: String, phase: String) = mutable.update { rows ->
        rows[sessionId]?.let { rows + (sessionId to it.copy(phase = phase, lastOutputAt = clock())) } ?: rows
    }
    fun retry(sessionId: String, reason: String) { pendingReasons[sessionId] = reason }
    fun remove(sessionId: String) { pendingReasons.remove(sessionId); mutable.update { it - sessionId } }
}

internal class ProviderActivityContext(val tracker: ProviderActivityTracker, val sessionId: String) :
    AbstractCoroutineContextElement(Key) {
    fun transport() = tracker.transport(sessionId)
    companion object Key : CoroutineContext.Key<ProviderActivityContext>
}
