package top.tianyan.app.plugin.ui.web

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Host-owned source instance. No endpoint or credential is given to Web content. */
class WebPluginAssetLease(
    assets: Map<String, ByteArray>,
    private val available: () -> Boolean = { true },
    private val release: suspend () -> Boolean = { true },
    private val load: (suspend () -> Map<String, ByteArray>)? = null,
    private val resolve: ((String) -> ByteArray?)? = null,
    private val request: (suspend (String) -> String?)? = null,
    private val stream: (suspend (String, suspend (String) -> Boolean) -> Boolean)? = null,
    private val write: (suspend (String, String) -> String?)? = null,
) {
    private val lock = Mutex()
    private var released = false
    @Volatile private var snapshot: Map<String, ByteArray>? = assets.mapValues { it.value.copyOf() }
    val isAvailable: Boolean get() = snapshot != null && runCatching(available).getOrDefault(false)
    internal fun assets(): Map<String, ByteArray> = checkNotNull(snapshot)
    internal suspend fun loadAssets(): Map<String, ByteArray> {
        check(isAvailable)
        val result = load?.invoke() ?: assets()
        check(isAvailable)
        return result
    }

    /**
     * Bounded blocking read for a live indexed source. A fixed-snapshot lease has no resolver and
     * answers only paths already in its map; a closed or drifted lease answers nothing.
     */
    internal fun resolveAsset(path: String): ByteArray? = if (isAvailable) resolve?.invoke(path) else null

    /**
     * Bounded dynamic read of the lease's own live service. A fixed snapshot lease has no dynamic
     * source, and a closed lease answers nothing, so the page can never reach another endpoint.
     */
    internal suspend fun requestPath(path: String): String? = if (isAvailable) request?.invoke(path) else null

    internal suspend fun writePath(path: String, body: String): String? =
        if (isAvailable) write?.invoke(path, body) else null

    /** Bounded incremental read of the lease's own live service; false stops a spent or closed source. */
    internal suspend fun streamPath(path: String, emit: suspend (String) -> Boolean): Boolean =
        if (isAvailable) stream?.invoke(path, emit) ?: false else false

    /** False keeps the resource in the activation ledger for a later cleanup attempt. */
    suspend fun close(): Boolean = lock.withLock {
        snapshot = null // Close admission even when the backing service fails to stop.
        if (released) true else try { release().also { released = it } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
}
