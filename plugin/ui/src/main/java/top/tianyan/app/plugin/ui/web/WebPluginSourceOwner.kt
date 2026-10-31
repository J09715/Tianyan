package top.tianyan.app.plugin.ui.web

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.tianyan.app.plugin.runtime.PluginActivation

/** Reserve cleanup before opening, including a resource returned while its generation is draining. */
internal class WebPluginSourceOwner(private val activation: PluginActivation, private val id: String) {
    private val phase = AtomicInteger(0) // reserved / opening / settled
    private val lease = AtomicReference<WebPluginAssetLease?>(null)
    init {
        activation.own(id) {
            phase.compareAndSet(0, 2)
            if (phase.get() == 1) false else lease.get()?.close() ?: true
        }
    }

    suspend fun open(factory: suspend () -> WebPluginAssetLease): WebPluginAssetLease {
        check(phase.compareAndSet(0, 1)) { "Web source is closed or already opened" }
        try {
            val acquired = factory()
            lease.set(acquired)
            check(activation.isAccepting && acquired.isAvailable) { "Web source no longer available" }
            return acquired
        } finally { phase.set(2) }
    }

    suspend fun close(): Boolean = withContext(NonCancellable) { activation.releaseOwned(id) }
}
