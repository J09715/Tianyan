package top.tianyan.app.runtime.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

data class HostAccessScope(val distroId: String, val pluginId: String = "sandbox", val version: String = "sandbox")
data class HostAccessConsent(val scope: HostAccessScope, val generation: Long, val expiresAt: Long)
data class SandboxGrantSnapshot(val actions: Set<String>, val generation: Long)

/** Developer mode enables the native consent UI; it never grants shell by itself. */
class FullAccessGrant(
    developerMode: Flow<Boolean>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private val monitor = Any()
    private var enabled = false
    private var generation = 0L
    private val mutableConsents = MutableStateFlow<List<HostAccessConsent>>(emptyList())
    val consents = mutableConsents.asStateFlow()

    init { scope.launch { developerMode.collect { value ->
        synchronized(monitor) {
            if (enabled != value) {
                enabled = value
                generation++
                mutableConsents.value = emptyList()
            }
        }
    } } }

    fun allow(target: HostAccessScope): Boolean = synchronized(monitor) {
        if (!enabled) return false
        require(target.distroId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        require(target.pluginId == "sandbox" || target.pluginId.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+")))
        require(target.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")))
        val consent = HostAccessConsent(target, ++generation, now() + LIFETIME_MILLIS)
        mutableConsents.value = mutableConsents.value.filter { it.scope != target && it.expiresAt > now() } + consent
        true
    }

    fun revoke(target: HostAccessScope) = synchronized(monitor) {
        mutableConsents.value = mutableConsents.value.filterNot { it.scope == target }
    }

    fun revokePlugin(distro: String, pluginId: String) = synchronized(monitor) {
        mutableConsents.value = mutableConsents.value.filterNot { it.scope.distroId == distro && it.scope.pluginId == pluginId }
    }

    fun revokeDistro(distro: String) = synchronized(monitor) {
        mutableConsents.value = mutableConsents.value.filterNot { it.scope.distroId == distro }
    }

    fun snapshot(target: HostAccessScope): SandboxGrantSnapshot = synchronized(monitor) {
        val consent = mutableConsents.value.singleOrNull { enabled && it.scope == target && it.expiresAt > now() }
        if (consent == null) SandboxGrantSnapshot(if (target.pluginId == "sandbox") SandboxCredentialStore.SANDBOX_ACTIONS else emptySet(), 0L)
        else SandboxGrantSnapshot(SandboxCredentialStore.FULL_ACCESS_ACTIONS, consent.generation)
    }

    /** Compatibility query intentionally never confers an unscoped shell grant. */
    fun actions(): Set<String> = SandboxCredentialStore.SANDBOX_ACTIONS

    override fun close() {
        synchronized(monitor) { enabled = false; mutableConsents.value = emptyList() }
        scope.cancel()
    }

    companion object { const val LIFETIME_MILLIS = 30L * 60 * 1000 }
}
