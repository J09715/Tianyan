package top.tianyan.app.harness.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpTransportType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout

class McpStdioTransportLifecycleTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val commandBuilder = McpCommandBuilder()

    private val echoServer = McpServerConfig(
        id = "echo",
        name = "echo",
        transportType = McpTransportType.STDIO,
        command = "/bin/echo",
        args = listOf("hello"),
    )

    private fun newTransport(factory: McpStdioChannelFactory): McpStdioTransport =
        McpStdioTransport(json, commandBuilder, factory)

    @Test
    fun `idle sweep reaps stale connections but skips busy ones`() = runBlocking {
        val factory = RecordingFactory()
        val transport = newTransport(factory)
        repeat(3) { i ->
            val server = echoServer.copy(id = "server-" + i)
            transport.injectConnection(server, FakeMcpChannel(aliveAfterOpen = true))
        }
        assertEquals(3, transport.test_connectionKeys().size)
        val busyId = transport.test_connectionKeys().first()
        transport.test_markConnectionInFlight(busyId, true)
        transport.rewindIdleForTest(McpStdioTransport.IDLE_TIMEOUT_MS + 60_000L)
        val reaped = transport.sweepIdleOnce(transport.clock())
        assertEquals("only the two idle ones should be reaped", 2, reaped)
        assertTrue(transport.test_connectionKeys().contains(busyId))
        transport.test_connectionKeys().forEach { id ->
            if (id == busyId) return@forEach
            assertFalse("idle connection must be evicted: " + id, transport.test_connectionKeys().contains(id))
        }
    }

    @Test
    fun `markActive refreshes last activity so a freshly used connection is not reaped`() = runBlocking {
        val transport = newTransport(RecordingFactory())
        val id = "fresh"
        transport.injectConnection(echoServer.copy(id = id), FakeMcpChannel(aliveAfterOpen = true))
        transport.rewindIdleForTest(McpStdioTransport.IDLE_TIMEOUT_MS + 60_000L)
        transport.test_markConnectionActive(id)
        assertEquals(0, transport.sweepIdleOnce(transport.clock()))
        assertTrue(transport.test_connectionKeys().contains(id))
    }

    @Test
    fun `startup timeout applies a fail-fast cooldown`() = runBlocking {
        val factory = HangingFactory()
        val transport = newTransport(factory)
        // First attempt hangs past the startup budget; withTimeoutOrNull caps the wait so the
        // test does not block for the full cooldown duration.
        withTimeoutOrNull<Unit>(McpStdioTransport.STARTUP_TIMEOUT_MS * 2 + 2_000L) {
            runCatching { transport.discover(echoServer) }
        }
        val second = runCatching { transport.discover(echoServer) }.exceptionOrNull()
        assertNotNull("second attempt must short-circuit via cooldown", second)
        assertTrue(
            "second failure should report cooldown, got: " + second!!.message,
            second.message.orEmpty().contains("冷却"),
        )
    }

    @Test
    fun `excess garbage frames trip the ignore threshold instead of stalling`() = runBlocking {
        val channel = FakeMcpChannel(aliveAfterOpen = true).also { runBlocking { it.feedGarbage(McpStdioTransport.MAX_IGNORED_FRAMES + 8) } }
        val transport = newTransport(FakeChannelFactory(channel))
        val failure: Throwable? = withTimeoutOrNull<Throwable?>(5_000L) {
            runCatching { transport.discover(echoServer) }.exceptionOrNull()
        }
        assertTrue("discover must not hang past the ignore-frame threshold", failure != null)
        assertTrue("transport must not retain a poisoned channel", transport.test_connectionKeys().isEmpty())
    }

    @Test
    fun `process death during request is reported instead of stalling`() = runBlocking {
        val channel = FakeMcpChannel(aliveAfterOpen = true)
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        channel.killIncoming("server crashed")
        val failure = withTimeoutOrNull<Throwable?>(5_000L) {
        runCatching { transport.discover(echoServer) }.exceptionOrNull()
        }
        assertNotNull("discovery must surface channel death as a failure", failure)
        assertFalse(
            "cooldown must NOT trigger on channel death (subprocess died, not sandbox)",
            failure!!.message.orEmpty().contains("冷却"),
        )
    }

    @Test
    fun `request timeout surfaces as a plain failure instead of pseudo cancellation`() = runBlocking {
        val channel = FakeMcpChannel(aliveAfterOpen = true)
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        transport.requestTimeoutOverrideMs = 150L
        val failure = withTimeoutOrNull<Throwable?>(5_000L) {
            runCatching { transport.discover(echoServer) }.exceptionOrNull()
        }
        assertNotNull("request must fail instead of hanging", failure)
        // 超时绝不能以 TimeoutCancellationException（CancellationException 的子类）暴露：
        // 否则沿 McpManager/ToolExecutor 的取消透传链会被当成"用户取消"，整个回合静默中止
        assertFalse(
            "timeout must not look like cancellation, got: " + failure!!::class.simpleName,
            failure is CancellationException,
        )
        assertTrue(
            "timeout message must name the request, got: " + failure.message,
            failure.message.orEmpty().contains("超时"),
        )
        assertTrue(
            "timed-out connection must be discarded so a possibly side-effecting call is never replayed",
            transport.test_connectionKeys().isEmpty(),
        )
    }

    @Test
    fun `outer caller cancellation still propagates while waiting for a response`() = runBlocking {
        val channel = FakeMcpChannel(aliveAfterOpen = true)
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        var caught: Throwable? = null
        try {
            withTimeout(McpStdioTransport.STARTUP_TIMEOUT_MS * 2) {
                transport.discover(echoServer)
            }
        } catch (t: Throwable) {
            caught = t
        }
        // 真实的外层取消（如 McpManager 发现 8s 总超时）必须原样透传，不得被转换成普通异常
        assertTrue(
            "caller cancellation must propagate as cancellation, got: " + caught?.let { it::class.simpleName },
            caught is CancellationException,
        )
    }

    @Test
    fun `cooldown is cleared when a subsequent startup succeeds`() = runBlocking {
        val factory = ConditionalFactory(
            first = HangingFactory(),
            second = FakeChannelFactory(RespondingMcpChannel()),
        )
        val transport = newTransport(factory)
        withTimeoutOrNull<Unit>(McpStdioTransport.STARTUP_TIMEOUT_MS * 2 + 2_000L) {
            runCatching { transport.discover(echoServer) }
            factory.switch()
            // Manual checks intentionally bypass cooldown. A successful startup must clear it.
            assertTrue(transport.check(echoServer).online)
        }
        val third = runCatching { transport.discover(echoServer) }
        assertTrue("discover should reuse the successful connection, got: " + third.exceptionOrNull()?.message, third.isSuccess)
    }

    @Test
    fun `discovery is not blocked by an in-flight tools call on the same connection`() = runBlocking {
        val channel = GatedMcpChannel()
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        transport.requestTimeoutOverrideMs = 30_000L

        val call = async(Dispatchers.IO) { transport.execute(echoServer, "slow_tool", JsonObject(emptyMap())) }
        channel.callRequested.await() // 在途调用已注册等待者并写出请求
        val discovered = withTimeoutOrNull(5_000L) { transport.discover(echoServer) }
        assertNotNull("发现必须在在途 tools/call 期间完成（多路复用；旧实现在此阻塞）", discovered)
        assertTrue(discovered!!.isEmpty())

        channel.callGate.complete(Unit)
        val callResult = withTimeoutOrNull(10_000L) { call.await() }
        assertNotNull("放行后在途调用应正常完成", callResult)
        assertTrue(callResult!!.first)
    }

    @Test
    fun `late response after a timeout discards the connection`() = runBlocking {
        val channel = DelayedRespondingMcpChannel()
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        transport.requestTimeoutOverrideMs = 150L

        val first = runCatching { transport.discover(echoServer) }
        assertTrue("首次发现应因超时失败", first.isFailure)
        assertTrue(
            "迟到响应所在连接必须被丢弃，避免后续调用复用可能已产生副作用的会话",
            transport.test_connectionKeys().isEmpty(),
        )
    }

    @Test
    fun `read-only request timeout keeps the connection and sends cancellation`() = runBlocking {
        val channel = HangingAfterInitChannel()
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        transport.requestTimeoutOverrideMs = 150L

        val failure = withTimeoutOrNull(5_000L) {
            runCatching { transport.discover(echoServer) }.exceptionOrNull()
        }
        assertNotNull("discover must fail with a timeout", failure)
        assertTrue(
            "read-only timeout must not discard a healthy multiplexed session",
            transport.test_connectionKeys().contains(echoServer.id),
        )
        assertEquals("timed-out waiter must be removed from the pending table", 0, transport.test_pendingCount(echoServer.id))
        val cancelled = withTimeoutOrNull(2_000L) { channel.cancelled.await() }
        assertNotNull("client must send notifications/cancelled", cancelled)
        assertTrue(cancelled!!.contains("notifications/cancelled"))
    }

    @Test
    fun `tools call timeout still discards the connection`() = runBlocking {
        val channel = HangingAfterInitChannel()
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        transport.requestTimeoutOverrideMs = 150L

        val failure = withTimeoutOrNull(5_000L) {
            runCatching { transport.execute(echoServer, "slow", JsonObject(emptyMap())) }.exceptionOrNull()
        }
        assertNotNull("tools/call must fail with a timeout", failure)
        assertTrue(
            "side-effecting call timeout must discard the connection",
            transport.test_connectionKeys().isEmpty(),
        )
    }

    @Test
    fun `tools list changed notification emits catalog change`() = runBlocking {
        val channel = FakeMcpChannel(aliveAfterOpen = true)
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        val received = CompletableDeferred<String>()
        val job = launch { transport.toolListChanged.collect { received.complete(it) } }
        delay(100) // 等收集器订阅完成，避免无订阅时发出的通知被 SharedFlow 丢弃

        channel.incoming.send("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""")
        assertEquals(echoServer.id, withTimeoutOrNull(2_000L) { received.await() })
        job.cancelAndJoin()
    }

    /** initialize 正常响应、其余请求永不响应：验证只读超时与 tools/call 超时的分流。 */
    private class HangingAfterInitChannel : McpStdioChannel {
        override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
        @Volatile private var alive = true
        override val isAlive: Boolean get() = alive
        val cancelled = CompletableDeferred<String>()

        override suspend fun writeLine(line: String) {
            if ("notifications/cancelled" in line) {
                cancelled.complete(line)
                return
            }
            val id = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1) ?: return
            if ("\"method\":\"initialize\"" in line) {
                incoming.send("{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{\"protocolVersion\":\"$MCP_PROTOCOL_VERSION\"}}")
            }
        }

        override suspend fun close() {
            alive = false
            incoming.close()
        }
    }

    /** tools/call 响应被门禁扣住的多路复用测试通道：其余请求即时回应。 */
    private class GatedMcpChannel : McpStdioChannel {
        override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
        @Volatile private var alive = true
        override val isAlive: Boolean get() = alive
        private val fakeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** tools/call 请求已写出（等待者已注册）。 */
        val callRequested = CompletableDeferred<Unit>()
        /** 放行 tools/call 的迟响应。 */
        val callGate = CompletableDeferred<Unit>()

        override suspend fun writeLine(line: String) {
            val id = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1) ?: return
            val result = when {
                "\"method\":\"initialize\"" in line -> "{\"protocolVersion\":\"$MCP_PROTOCOL_VERSION\"}"
                "\"method\":\"tools/list\"" in line -> "{\"tools\":[]}"
                "\"method\":\"tools/call\"" in line -> {
                    callRequested.complete(Unit)
                    fakeScope.launch {
                        callGate.await()
                        incoming.send("{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{\"isError\":false,\"content\":[]}}")
                    }
                    return
                }
                else -> return
            }
            incoming.send("{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":$result}")
        }

        override suspend fun close() {
            alive = false
            incoming.close()
        }
    }

    /** 首次 initialize 异步迟响应（请求方必先超时），之后所有请求即时回应。 */
    private class DelayedRespondingMcpChannel : McpStdioChannel {
        override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
        @Volatile private var alive = true
        override val isAlive: Boolean get() = alive
        private var initializeCount = 0
        private val fakeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        override suspend fun writeLine(line: String) {
            val id = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1) ?: return
            val isInitialize = "\"method\":\"initialize\"" in line
            val result = when {
                isInitialize -> "{\"protocolVersion\":\"$MCP_PROTOCOL_VERSION\"}"
                "\"method\":\"tools/list\"" in line -> "{\"tools\":[]}"
                else -> return
            }
            if (isInitialize && initializeCount++ == 0) {
                fakeScope.launch {
                    delay(400)
                    incoming.send("{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":$result}")
                }
            } else {
                incoming.send("{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":$result}")
            }
        }

        override suspend fun close() {
            alive = false
            // Model process shutdown: stop the delayed writer before closing its stdout.
            fakeScope.coroutineContext[Job]?.cancelAndJoin()
            incoming.close()
        }
    }

    @Test
    fun `circuit breaker error frame routes to in-flight request without hanging`() = runBlocking {
        val channel = CircuitBreakerMcpChannel()
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        transport.requestTimeoutOverrideMs = 5_000L

        val result = withTimeoutOrNull(2_000L) {
            transport.execute(echoServer, "oversized_tool", JsonObject(emptyMap()))
        }
        assertNotNull("熔断错误帧必须即时路由给等待者，不得超时挂起", result)
        assertFalse(result!!.first)
        assertTrue(result.second.contains("熔断保护") || result.second.contains("-32603"))
        assertTrue("熔断错误属于业务级保护，通道必须保留复用", transport.test_connectionKeys().contains(echoServer.id))
    }

    private class CircuitBreakerMcpChannel : McpStdioChannel {
        override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
        @Volatile private var alive = true
        override val isAlive: Boolean get() = alive

        override suspend fun writeLine(line: String) {
            val id = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1) ?: return
            if ("\"method\":\"initialize\"" in line) {
                incoming.send("{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{\"protocolVersion\":\"$MCP_PROTOCOL_VERSION\"}}")
            } else if ("\"method\":\"tools/call\"" in line) {
                // 模拟单行输出超限熔断：下发无 id 的 -32603 错误帧
                incoming.send("""{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"MCP STDIO 单行输出超过安全上限 (1MB)，已触发移动端熔断保护"}}""")
            }
        }

        override suspend fun close() {
            alive = false
            incoming.close()
        }
    }

    @Test
    fun `cancellation during write propagates and leaves no pending waiter`() = runBlocking {
        val channel = BlockingWriteMcpChannel()
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)

        var caught: Throwable? = null
        val job = launch {
            try {
                transport.discover(echoServer)
            } catch (t: Throwable) {
                caught = t
            }
        }
        delay(200) // 等请求注册并挂起在 writeLine 上
        job.cancelAndJoin()

        assertTrue(
            "写路径取消必须以 CancellationException 透传，不得包装成通道异常销毁连接，got: " +
                caught?.let { it::class.simpleName },
            caught is CancellationException,
        )
        assertEquals("等待者必须同步移除，否则 inFlight 永真、连接无法被空闲回收", 0, transport.test_pendingCount(echoServer.id))
        assertTrue("取消不代表通道损坏，连接必须保留", transport.test_connectionKeys().contains(echoServer.id))
    }

    /** writeLine 永久挂起的通道：验证写路径上的取消传播与等待者清理。 */
    private class BlockingWriteMcpChannel : McpStdioChannel {
        override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
        @Volatile private var alive = true
        override val isAlive: Boolean get() = alive
        private val writeGate = CompletableDeferred<Unit>()

        override suspend fun writeLine(line: String) {
            writeGate.await()
        }

        override suspend fun close() {
            alive = false
            incoming.close()
        }
    }

    private class FakeMcpChannel(
        private val aliveAfterOpen: Boolean,
        // Tests preload more than MAX_IGNORED_FRAMES before discover() starts consuming.
        // An unbounded fake keeps that setup synchronous without coupling it to producer timing.
        override val incoming: Channel<String> = Channel(capacity = Channel.UNLIMITED),
    ) : McpStdioChannel {
        @Volatile
        private var alive: Boolean = aliveAfterOpen
        override val isAlive: Boolean get() = alive
        override suspend fun writeLine(line: String) { if (!alive) error("write after close") }
        override suspend fun close() { alive = false; incoming.close() }
        fun killIncoming(reason: String) { alive = false; incoming.close(IllegalStateException(reason)) }
        suspend fun feedGarbage(count: Int) { for (i in 0 until count) incoming.send("garbage " + i) }
    }

    private class FakeChannelFactory(private val channel: McpStdioChannel) : McpStdioChannelFactory {
        override suspend fun open(server: McpServerConfig): McpStdioChannel = channel
    }

    /** Minimal protocol-aware fake used when a test needs initialization to really succeed. */
    private class RespondingMcpChannel : McpStdioChannel {
        override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
        @Volatile private var alive = true
        override val isAlive: Boolean get() = alive

        override suspend fun writeLine(line: String) {
            val id = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1) ?: return
            val result = when {
                "\"method\":\"initialize\"" in line -> "{\"protocolVersion\":\"$MCP_PROTOCOL_VERSION\"}"
                "\"method\":\"tools/list\"" in line -> "{\"tools\":[]}"
                else -> return
            }
            incoming.send("{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":$result}")
        }

        override suspend fun close() {
            alive = false
            incoming.close()
        }
    }

    private class HangingFactory : McpStdioChannelFactory {
        var openAttempts = 0
        override suspend fun open(server: McpServerConfig): McpStdioChannel {
            openAttempts++
            awaitCancellation()
        }
    }

    private class ConditionalFactory(
        first: McpStdioChannelFactory,
        second: McpStdioChannelFactory,
    ) : McpStdioChannelFactory {
        private var current: McpStdioChannelFactory = first
        private val next: McpStdioChannelFactory = second
        fun switch() { current = next }
        override suspend fun open(server: McpServerConfig): McpStdioChannel = current.open(server)
    }

    private class RecordingFactory : McpStdioChannelFactory {
        var created = 0
        override suspend fun open(server: McpServerConfig): McpStdioChannel {
            created++
            return FakeMcpChannel(aliveAfterOpen = true)
        }
    }
}

internal fun McpStdioTransport.injectConnection(server: McpServerConfig, channel: McpStdioChannel) {
    this.injectConnectionForTest(server, channel)
}

/** Test-only lifecycle probes. Reflection stays out of the production artifact. */
internal fun McpStdioTransport.rewindIdleForTest(ageMs: Long) {
    val target = clock() - ageMs
    reflectedConnections().values.forEach { connection ->
        val field = connection.javaClass.getDeclaredField("lastActivityMs").apply { isAccessible = true }
        field.setLong(connection, target)
    }
}

@Suppress("FunctionName")
internal fun McpStdioTransport.test_connectionKeys(): Set<String> {
    val field = McpStdioTransport::class.java.getDeclaredField("connections").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val map = field.get(this) as ConcurrentHashMap<String, Any>
    return map.keys.toSet()
}

@Suppress("FunctionName")
internal fun McpStdioTransport.test_markConnectionActive(serverId: String) {
    val connections = reflectedConnections()
    val connection = connections[serverId] ?: error("no connection for $serverId")
    val method = connection.javaClass.getDeclaredMethod("markActive").apply { isAccessible = true }
    method.invoke(connection)
}

@Suppress("FunctionName")
internal fun McpStdioTransport.test_markConnectionInFlight(serverId: String, inFlight: Boolean) {
    val connections = reflectedConnections()
    val connection = connections[serverId] ?: error("no connection for $serverId")
    // 多路复用后 inFlight = 等待表非空（旧实现为 mutex.isLocked）
    val field = connection.javaClass.getDeclaredField("pending").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val pending = field.get(connection) as ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>
    if (inFlight) pending.putIfAbsent("test-in-flight", CompletableDeferred()) else pending.clear()
}

@Suppress("FunctionName")
internal fun McpStdioTransport.test_pendingCount(serverId: String): Int {
    val connections = reflectedConnections()
    val connection = connections[serverId] ?: return 0
    val field = connection.javaClass.getDeclaredField("pending").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val pending = field.get(connection) as ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>
    return pending.size
}

private fun McpStdioTransport.reflectedConnections(): ConcurrentHashMap<String, Any> {
    val field = McpStdioTransport::class.java.getDeclaredField("connections").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as ConcurrentHashMap<String, Any>
}
