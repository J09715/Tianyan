package top.tianyan.app.runtime.service

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import top.tianyan.app.runtime.bridge.HostBridgeHttp

/** Owned demo service: loopback only, ephemeral port, immutable files and one per-instance secret. */
class LoopbackWebAssetService private constructor(input: Map<String, ByteArray>) {
    private val assets = input.mapValues { it.value.copyOf() }.toMutableMap()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + owner)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val prefix = "/${UUID.randomUUID()}/"
    private val authorization = "Bearer ${UUID.randomUUID()}${UUID.randomUUID()}"
    private val job = scope.launch {
        try {
            while (!server.isClosed) {
                val client = server.accept()
                if (clients.size >= 16) { client.close(); continue }
                clients += client
                launch { serve(client) }
            }
        } catch (_: Exception) { // A closed socket wakes accept on disposal.
        } finally {
            server.close()
            clients.forEach { runCatching { it.close() } }
        }
    }

    val source = ManagedWebAssets(server.localPort, prefix, assets.mapValues { ManagedWebAssets.digest(it.value) },
        authorization, isAvailable = { !server.isClosed && job.isActive }, release = { close() })

    suspend fun close(): Boolean {
        server.close()
        clients.forEach { runCatching { it.close() } }
        owner.cancelAndJoin()
        clients.clear()
        synchronized(assets) { assets.clear() }
        return server.isClosed && clients.isEmpty()
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                it.soTimeout = 2000
                val request = HostBridgeHttp.parseHttpRequest(it.getInputStream()) ?: return
                val bytes = synchronized(assets) {
                    if (request.method == "GET" && request.body.isEmpty() &&
                        request.headers["authorization"] == authorization && request.path.startsWith(prefix))
                        assets[request.path.removePrefix(prefix)] else null
                }
                val status = if (bytes == null) "404 Not Found" else "200 OK"
                val payload = bytes ?: ByteArray(0)
                it.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: ${payload.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                it.getOutputStream().write(payload)
            }
        } catch (_: Exception) {
        } finally { clients -= client; runCatching { client.close() } }
    }

    companion object {
        fun start(assets: Map<String, ByteArray>): LoopbackWebAssetService {
            require(assets.size in 1..63 && "index.html" in assets)
            require(assets.keys.all(ManagedWebAssets::safePath))
            require(assets.values.sumOf { it.size.toLong() } <= 2 * 1024 * 1024)
            return LoopbackWebAssetService(assets)
        }
    }
}
