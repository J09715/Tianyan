package top.tianyan.app.harness.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.common.McpCatalogRevisions
import top.tianyan.app.core.model.McpServerConfig
import java.util.UUID

class McpCatalogRestartTest {
    private val json = Json { ignoreUnknownKeys = true }
    private class Server(val version: Int, private val json: Json, val held: Boolean = false) : McpStdioChannel {
        override val incoming = Channel<String>(Channel.UNLIMITED)
        override var isAlive = true
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun writeLine(line: String) {
            val request = json.parseToJsonElement(line).jsonObject
            val id = request["id"] ?: return
            val result = when (request["method"]?.jsonPrimitive?.content) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{}}"""
                "tools/list" -> """{"tools":[{"name":"v$version","inputSchema":{"type":"object"}}]}"""
                else -> {
                    calls++
                    started.complete(Unit)
                    if (held) release.await()
                    """{"content":[{"type":"text","text":"v$version"}]}"""
                }
            }
            incoming.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        override suspend fun close() { isAlive = false; incoming.close(); closed.complete(Unit) }
    }

    @Test fun `upgrade drains in flight call then starts new version exactly once`() = runBlocking {
        withTimeout(10_000) {
            val id = "upgrade-${UUID.randomUUID()}"
            val channels = mutableListOf<Server>()
            val transport = McpStdioTransport(json, McpCommandBuilder(), object : McpStdioChannelFactory {
                override suspend fun open(server: McpServerConfig) = Server(channels.size + 1, json, channels.isEmpty()).also { channels += it }
            })
            val server = McpServerConfig(id, id, command = "node", catalogDistribution = "debian")
            val first = async { transport.execute(server, "v1", JsonObject(emptyMap())) }
            while (channels.isEmpty()) yield()
            val old = channels.first()
            old.started.await()
            McpCatalogRevisions.upgraded("debian", id)
            val next = async { transport.discover(server) }
            assertNull(withTimeoutOrNull(100) { next.await() })
            assertTrue(old.isAlive)
            withContext(McpConnectionScope("$id@environment:local:ubuntu")) {
                assertTrue(transport.check(server.copy(catalogDistribution = "ubuntu")).online)
            }
            old.release.complete(Unit)
            assertEquals(true to "v1", first.await())
            assertEquals(listOf("v3"), next.await().map { it.name })
            old.closed.await()
            assertEquals(1, old.calls)
            assertEquals(3, channels.size)
            transport.closeConnection(id)
        }
    }

    @Test fun `upgrade does not retire a connection in another distribution`() = runBlocking {
        withTimeout(5_000) {
            val id = "isolation-${UUID.randomUUID()}"
            val channel = Server(1, json)
            val transport = McpStdioTransport(json, McpCommandBuilder(), object : McpStdioChannelFactory {
                override suspend fun open(server: McpServerConfig): McpStdioChannel = channel
            })
            val server = McpServerConfig(id, id, command = "node", catalogDistribution = "ubuntu")
            assertTrue(transport.check(server).online)
            McpCatalogRevisions.upgraded("debian", id)
            assertEquals(true to "v1", transport.execute(server, "v1", JsonObject(emptyMap())))
            assertTrue(channel.isAlive)
            transport.closeConnection(id)
        }
    }

    @Test fun `cancelling a replacement waiter does not cancel or replay old call`() = runBlocking {
        withTimeout(10_000) {
            val id = "cancel-${UUID.randomUUID()}"
            val channels = mutableListOf<Server>()
            val transport = McpStdioTransport(json, McpCommandBuilder(), object : McpStdioChannelFactory {
                override suspend fun open(server: McpServerConfig) = Server(channels.size + 1, json, channels.isEmpty()).also { channels += it }
            })
            val server = McpServerConfig(id, id, command = "node", catalogDistribution = "debian")
            val first = async { transport.execute(server, "v1", JsonObject(emptyMap())) }
            while (channels.isEmpty()) yield()
            val old = channels.first()
            old.started.await()
            McpCatalogRevisions.upgraded("debian", id)
            val cancelled = async { transport.discover(server) }
            assertNull(withTimeoutOrNull(100) { cancelled.await() })
            cancelled.cancelAndJoin()
            assertTrue(old.isAlive)
            old.release.complete(Unit)
            assertEquals(true to "v1", first.await())
            old.closed.await()
            assertEquals(true to "v2", transport.execute(server, "v2", JsonObject(emptyMap())))
            assertEquals(1, old.calls)
            assertEquals(1, channels.last().calls)
            transport.closeConnection(id)
        }
    }

    @Test fun `schema expiration targets only upgraded distribution`() = runBlocking {
        withTimeout(5_000) {
            val id = "cache-${UUID.randomUUID()}"
            val local = "$id@environment:local:debian"
            val other = "$id@environment:local:ubuntu"
            val cache = java.util.concurrent.ConcurrentHashMap<String, CachedMcpTools>()
            cache[local] = CachedMcpTools("old", emptyList(), "debian", 0)
            cache[other] = CachedMcpTools("other", emptyList(), "ubuntu", 0)
            val expired = CompletableDeferred<String>()
            val watching = expireCatalogTools(cache) { expired.complete(it) }
            try {
                McpCatalogRevisions.upgraded("debian", id)
                assertEquals(local, expired.await())
                assertFalse(cache.containsKey(local))
                assertTrue(cache.containsKey(other))
            } finally { watching.cancelAndJoin() }
        }
    }

    @Test fun `catalog cache expires after ttl`() {
        val now = System.currentTimeMillis()
        val fresh = CachedMcpTools("f", emptyList(), "debian", 0, cachedAtMs = now)
        val stale = CachedMcpTools("f", emptyList(), "debian", 0, cachedAtMs = now - MCP_CATALOG_TTL_MS - 1)
        assertTrue(fresh.isFresh(now))
        assertFalse(stale.isFresh(now))
    }

    @Test fun `upgrade during startup cannot dispatch on a stale generation`() = runBlocking {
        withTimeout(5_000) {
            val id = "startup-${UUID.randomUUID()}"
            val opening = CompletableDeferred<Unit>()
            val finishOpening = CompletableDeferred<Unit>()
            val channels = mutableListOf<Server>()
            val transport = McpStdioTransport(json, McpCommandBuilder(), object : McpStdioChannelFactory {
                override suspend fun open(server: McpServerConfig): McpStdioChannel {
                    if (channels.isEmpty()) { opening.complete(Unit); finishOpening.await() }
                    return Server(channels.size + 1, json).also { channels += it }
                }
            })
            val server = McpServerConfig(id, id, command = "node", catalogDistribution = "debian")
            val checking = async { transport.check(server) }
            opening.await()
            McpCatalogRevisions.upgraded("debian", id)
            finishOpening.complete(Unit)
            assertTrue(checking.await().online)
            assertEquals(2, channels.size)
            assertFalse(channels.first().isAlive)
            assertEquals(0, channels.first().calls)
            assertEquals(true to "v2", transport.execute(server, "v2", JsonObject(emptyMap())))
            transport.closeConnection(id)
        }
    }
}
