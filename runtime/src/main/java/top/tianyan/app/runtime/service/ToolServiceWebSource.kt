package top.tianyan.app.runtime.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Native-resolved target of an installed PROCESS tool service. Port, base path and any
 * authorization are read from validated native state, never from a Web message, package field or
 * page argument. [ownerPluginId] and [distroId] only label the source for admission bookkeeping.
 */
data class ToolServiceDescriptor(
    val port: Int,
    val basePath: String,
    val ownerPluginId: String,
    val distroId: String,
    val authorization: String? = null,
    val authentication: ToolServiceAuthentication? = null,
) {
    init {
        require(port in 1..65535) { "Tool service port invalid" }
        require(IndexedManagedWebAssets.validBasePath(basePath)) { "Tool service path invalid" }
        require(ownerPluginId.length in 1..128 && ownerPluginId.none(Char::isISOControl)) { "Tool service owner invalid" }
        require(distroId.length in 1..128 && distroId.none(Char::isISOControl)) { "Tool service system invalid" }
    }
}

/**
 * Page-owned source over an already-running installed tool service.
 *
 * It only reads bounded static responses from loopback, pins every path on first read and never
 * starts, stops or restarts the tool service, so closing the page never disturbs the tool center's
 * existing service. Only a local backend can offer it; the source refuses to hand out a lease
 * before allocation when the active backend is remote.
 */
class ToolServiceWebSource(
    private val descriptor: ToolServiceDescriptor,
    private val isLocal: () -> Boolean,
    private val startupTimeoutMillis: Long = 15_000,
) {
    private val assets = IndexedManagedWebAssets(
        port = descriptor.port,
        basePath = descriptor.basePath,
        authorization = descriptor.authorization,
        authentication = descriptor.authentication,
        available = { isLocal() },
    )
    private val client = ManagedWebServiceClient(
        port = descriptor.port,
        basePath = descriptor.basePath,
        authorization = descriptor.authorization,
        authentication = descriptor.authentication,
        available = { isAvailable },
    )

    init {
        require(startupTimeoutMillis in 1..15_000) { "Tool service wait out of range" }
        check(isLocal()) { "Tool service page source requires a local backend" }
    }

    val isAvailable: Boolean get() = assets.isAvailable

    /** Probe first, snapshot second: no document exists until the service answers within the wait. */
    suspend fun snapshot(): Map<String, ByteArray> = withTimeout(startupTimeoutMillis) {
        while (true) {
            coroutineContext.ensureActive()
            runCatching { assets.snapshot() }.getOrNull()?.let { return@withTimeout it }
            delay(100)
        }
        error("unreachable")
    }

    fun resolve(path: String): ByteArray? = assets.resolve(path)

    /** Bounded dynamic read of this service only; never another endpoint or credential. */
    suspend fun read(path: String): String? = client.read(path)

    suspend fun write(path: String, body: String): String? = client.write(path, body)

    suspend fun stream(path: String, emit: suspend (String) -> Boolean): Boolean = client.stream(path, emit)

    suspend fun close(): Boolean {
        client.close()
        return assets.close()
    }
}
