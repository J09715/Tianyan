package top.tianyan.app.plugin.runtime

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Host-owned scope. None of these identity fields are accepted from JavaScript. */
data class PluginCapabilityIdentity(
    val pluginId: String, val version: String, val generation: Long,
    val origin: String, val environment: String,
)

data class PluginCapability(val action: String, val target: String)
data class PluginCapabilityCall(val id: String, val capability: PluginCapability, val text: String? = null)
data class PluginCapabilityPrompt(
    val token: String, val identity: PluginCapabilityIdentity, val call: PluginCapabilityCall,
)
enum class PluginGrantChoice { DENY, ONCE, SESSION }
data class PluginCapabilityResult(val id: String, val success: Boolean, val text: String)

/**
 * Per-document application-layer gate, independent of the shared HostBridge key.
 * Grants expire, are exact action/target matches, and never survive a new document or process.
 * Read-only capabilities may cache a longer session grant; state-changing ones stay short-lived or
 * one-shot. Nothing is persisted, and any generation switch re-asks. Executors must cooperate with
 * cancellation; revocation cannot undo an existing side effect.
 */
class PluginCapabilitySession(
    val identity: PluginCapabilityIdentity,
    executors: Map<PluginCapability, suspend (PluginCapabilityCall) -> String>,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val grantLifetimeMillis: Long = 300_000,
    private val longLivedGrantMillis: Long = 1_800_000,
    private val approvalTimeoutMillis: Long = 60_000,
    private val executionTimeoutMillis: Long = 10_000,
    private val isAvailable: () -> Boolean = { true },
    oneShotCapabilities: Set<PluginCapability> = emptySet(),
    longLivedCapabilities: Set<PluginCapability> = emptySet(),
) : AutoCloseable {
    private val monitor = Any()
    private val executors = executors.toMap()
    private val oneShotCapabilities = oneShotCapabilities.toSet() + PluginCapabilityInput.ONE_SHOT
    // Only read-only actions may hold a longer grant; a one-shot action can never gain one by naming it.
    private val longLivedCapabilities = longLivedCapabilities - PluginCapabilityInput.ONE_SHOT
    private val grants = mutableMapOf<PluginCapability, Long>()
    private val seen = mutableSetOf<String>()
    private val jobs = mutableSetOf<Job>()
    private var epoch = 0L
    private var closed = false
    private var decision: CompletableDeferred<PluginGrantChoice>? = null
    private val mutablePrompt = MutableStateFlow<PluginCapabilityPrompt?>(null)
    val prompt = mutablePrompt.asStateFlow()
    private val mutableGrantCount = MutableStateFlow(0)
    val grantCount = mutableGrantCount.asStateFlow()

    init {
        require(grantLifetimeMillis in 1..300_000 && longLivedGrantMillis in grantLifetimeMillis..3_600_000 &&
            approvalTimeoutMillis in 1..60_000 && executionTimeoutMillis in 1..30_000)
        require(identity.pluginId.isNotBlank() && identity.version.isNotBlank() && identity.environment.isNotBlank())
    }

    /** Main-frame and origin assertions must come from WebView, never the message body. */
    suspend fun call(call: PluginCapabilityCall, sourceOrigin: String, mainFrame: Boolean): PluginCapabilityResult = coroutineScope {
        fun denied(reason: String) = PluginCapabilityResult(call.id, false, reason)
        val job = currentCoroutineContext()[Job]!!
        var approval: CompletableDeferred<PluginGrantChoice>? = null
        val admittedEpoch: Long
        synchronized(monitor) {
            if (closed || !isAvailable() || !mainFrame || sourceOrigin != identity.origin) return@coroutineScope denied("origin_denied")
            if (!call.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return@coroutineScope denied("invalid_request")
            if (call.capability !in executors) return@coroutineScope denied("capability_denied")
            if (!PluginCapabilityInput.valid(call)) return@coroutineScope denied("invalid_request")
            if (call.id in seen) return@coroutineScope denied("replayed_request")
            if (seen.size >= 256) return@coroutineScope denied("session_limit")
            // Bound both work and native prompts; never queue arbitrary document requests.
            if (jobs.isNotEmpty()) return@coroutineScope denied("busy")
            seen += call.id
            admittedEpoch = epoch
            jobs += job
            val now = nowMillis()
            grants.entries.removeAll { now >= it.value }
            mutableGrantCount.value = grants.size
            if (call.capability !in grants) {
                approval = CompletableDeferred()
                decision = approval
                mutablePrompt.value = PluginCapabilityPrompt(UUID.randomUUID().toString(), identity, call)
            }
        }
        try {
            val choice = approval?.let { withTimeoutOrNull(approvalTimeoutMillis) { it.await() } }
            synchronized(monitor) {
                if (closed || !isAvailable() || epoch != admittedEpoch) return@coroutineScope denied("revoked")
                if (approval != null) {
                    if (decision === approval) {
                        decision = null
                        mutablePrompt.value = null
                    }
                    if (choice == null || choice == PluginGrantChoice.DENY) return@coroutineScope denied("permission_denied")
                    if (choice == PluginGrantChoice.SESSION && call.capability in oneShotCapabilities) return@coroutineScope denied("permission_denied")
                    if (choice == PluginGrantChoice.SESSION) {
                        val lifetime = if (call.capability in longLivedCapabilities) longLivedGrantMillis else grantLifetimeMillis
                        grants[call.capability] = nowMillis() + lifetime
                        mutableGrantCount.value = grants.size
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val text = withTimeoutOrNull(executionTimeoutMillis) { executors.getValue(call.capability)(call) }
                ?: return@coroutineScope denied("execution_timeout")
            currentCoroutineContext().ensureActive()
            synchronized(monitor) {
                if (closed || !isAvailable() || epoch != admittedEpoch) return@coroutineScope denied("revoked")
            }
            if (text.length > 32_768) denied("result_too_large") else PluginCapabilityResult(call.id, true, text)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            denied("execution_failed")
        } finally {
            synchronized(monitor) {
                jobs -= job
                if (approval != null && decision === approval) {
                    decision = null
                    mutablePrompt.value = null
                }
            }
        }
    }

    /** Called only by native confirmation UI. Stale dialog tokens cannot approve another call. */
    fun decide(token: String, choice: PluginGrantChoice): Boolean = synchronized(monitor) {
        if (closed || !isAvailable() || mutablePrompt.value?.token != token) return false
        val accepted = decision?.complete(choice) == true
        if (accepted) mutablePrompt.value = null
        accepted
    }

    fun revoke() = invalidate(false)
    override fun close() = invalidate(true)

    private fun invalidate(close: Boolean) {
        val active = synchronized(monitor) {
            closed = closed || close
            epoch++
            grants.clear()
            mutableGrantCount.value = 0
            decision?.complete(PluginGrantChoice.DENY)
            decision = null
            mutablePrompt.value = null
            jobs.toList()
        }
        active.forEach { it.cancel("Plugin permission revoked") }
    }
}
