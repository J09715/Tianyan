package top.tianyan.app.harness.mcp

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.McpConnectionState
import top.tianyan.app.core.model.McpServerConfig

class McpStdioSleepTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = McpServerConfig("sleep-test", "Sleep", command = "fake")
    private class ReplyChannel(private val json: Json, val hold: Boolean = false) : McpStdioChannel {
        override val incoming = Channel<String>(Channel.UNLIMITED)
        override var isAlive = true
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var failCloseOnce = false
        override suspend fun writeLine(line: String) {
            val request = json.parseToJsonElement(line).jsonObject
            val id = request["id"] ?: return
            val result = when (request["method"]?.jsonPrimitive?.content) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{}}"""
                "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                else -> {
                    calls++; started.complete(Unit)
                    if (hold) release.await()
                    """{"content":[{"type":"text","text":"ok"}]}"""
                }
            }
            incoming.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        override suspend fun close() {
            if (failCloseOnce) { failCloseOnce = false; error("close failed") }
            isAlive = false; incoming.close()
        }
    }

    @Test fun `long requests are protected and idle TTL begins after completion`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val now = AtomicLong(0)
            val channels = mutableListOf<ReplyChannel>()
            val transport = McpStdioTransport(json, McpCommandBuilder(), object : McpStdioChannelFactory {
                override suspend fun open(server: McpServerConfig) = ReplyChannel(json, channels.isEmpty()).also { channels += it }
            }, idleTimeoutMinutes = flowOf(1), clock = now::get)
            try {
                val call = async { transport.execute(server, "echo", JsonObject(emptyMap())) }
                while (channels.isEmpty()) yield()
                channels.single().started.await()
                now.set(20 * 60_000L)
                assertEquals(0, transport.sweepIdleOnce(now.get()))
                channels.single().release.complete(Unit)
                assertEquals(true to "ok", call.await())
                now.addAndGet(59_999)
                assertEquals(0, transport.sweepIdleOnce(now.get()))
                now.incrementAndGet()
                assertEquals(1, transport.sweepIdleOnce(now.get()))
                assertEquals(McpConnectionState.SLEEPING, transport.processStates.value[server.id])
                val calls = listOf(async { transport.execute(server, "echo", JsonObject(emptyMap())) },
                    async { transport.execute(server, "echo", JsonObject(emptyMap())) })
                calls.awaitAll().forEach { assertEquals(true to "ok", it) }
                assertEquals(2, channels.size)
                assertEquals(1, channels.first().calls)
                assertEquals(2, channels.last().calls)
                assertEquals(McpConnectionState.ONLINE, transport.processStates.value[server.id])
            } finally { transport.closeConnection(server.id) }
        }
    }

    @Test fun `TTL changes reclaim processes while cached readiness stays visible`() = runBlocking<Unit> {
        val now = AtomicLong(0)
        val ttl = MutableStateFlow(10)
        val channel = ReplyChannel(json)
        val transport = McpStdioTransport(json, McpCommandBuilder(), object : McpStdioChannelFactory {
            override suspend fun open(server: McpServerConfig) = channel
        }, idleTimeoutMinutes = ttl, clock = now::get)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val states = McpScopedConnectionStates(null, scope, transport.processStates)
        try {
            assertEquals(1, transport.discover(server).size)
            states.update(McpConnectionState.ONLINE, server.id)
            now.set(4 * 60_000L)
            assertEquals(0, transport.sweepIdleOnce(now.get()))
            ttl.value = 3
            assertEquals(1, transport.sweepIdleOnce(now.get()))
            withTimeout(2_000) { states.visible.first { it[server.id] == McpConnectionState.SLEEPING } }
            assertFalse(channel.isAlive)
            transport.closeConnection(server.id)
            assertFalse(transport.processStates.value.containsKey(server.id))
        } finally { scope.cancel(); transport.closeConnection(server.id) }
    }

    @Test fun `failed idle cleanup reports offline and still reclaims other servers`() = runBlocking<Unit> {
        val now = AtomicLong(0)
        val channels = mutableListOf<ReplyChannel>()
        val transport = McpStdioTransport(json, McpCommandBuilder(), object : McpStdioChannelFactory {
            override suspend fun open(server: McpServerConfig) = ReplyChannel(json).also { channels += it }
        }, idleTimeoutMinutes = flowOf(1), clock = now::get)
        val other = server.copy(id = "other")
        try {
            transport.discover(server); transport.discover(other)
            channels.first().failCloseOnce = true
            now.set(60_000)
            try { transport.sweepIdleOnce(now.get()); fail("must report cleanup failure") }
            catch (_: IllegalStateException) { }
            assertEquals(McpConnectionState.OFFLINE, transport.processStates.value[server.id])
            assertEquals(McpConnectionState.SLEEPING, transport.processStates.value[other.id])
            assertEquals(1, transport.sweepIdleOnce(now.get()))
            assertEquals(McpConnectionState.SLEEPING, transport.processStates.value[server.id])
        } finally { transport.closeConnection(server.id); transport.closeConnection(other.id) }
    }

    @Test fun `state projection never leaks another owner or replaces manual checking`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processes = MutableStateFlow(mapOf("server" to McpConnectionState.WAKING,
            "server@environment:other-owner" to McpConnectionState.SLEEPING))
        val states = McpScopedConnectionStates(null, scope, processes)
        try {
            states.update(McpConnectionState.ONLINE, "server")
            states.update(McpConnectionState.ONLINE, "server@environment:other-owner")
            withTimeout(2_000) { states.visible.first { it["server"] == McpConnectionState.WAKING } }
            assertEquals(setOf("server"), states.visible.value.keys)
            states.update(McpConnectionState.CHECKING, "server")
            withTimeout(2_000) { states.visible.first { it["server"] == McpConnectionState.CHECKING } }
        } finally { scope.cancel() }
    }
}
