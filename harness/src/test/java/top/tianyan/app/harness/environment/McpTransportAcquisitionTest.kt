package top.tianyan.app.harness.environment

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.harness.core.ResourceCleanupException
import top.tianyan.app.harness.mcp.*
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext

class McpTransportAcquisitionTest {
    private class TestChannel : McpStdioChannel {
        var closes = 0
        var failures = 1
        override val incoming = Channel<String>(Channel.UNLIMITED)
        override var isAlive = true
        override suspend fun writeLine(line: String) {}
        override suspend fun close() {
            closes++
            if (failures-- > 0) error("channel cleanup")
            isAlive = false; incoming.close()
        }
    }

    @Test fun `connection registration failure stays primary when channel rollback fails`() = runTest {
        val fixture = McpStartupFixture()
        val channel = TestChannel()
        val factory = object : McpStdioChannelFactory {
            override suspend fun open(server: McpServerConfig) = channel
        }
        val transport = McpStdioTransport(Json, McpCommandBuilder(), factory)
        try {
            withContext(ExecutionEnvironmentContext(fixture.World()) { error("registration denied") }) {
                transport.discover(fixture.server)
            }
            fail("registration failure lost")
        } catch (t: IllegalStateException) {
            assertEquals("registration denied", t.message)
            assertTrue(generateSequence(t as Throwable) { it.cause }.any { it.suppressed.isNotEmpty() })
        }
        transport.closeConnection(fixture.server.id)
        assertEquals(2, channel.closes)
    }

    @Test fun `invalidation attempts pending factory cleanup even when connection close fails`() = runTest {
        val fixture = McpStartupFixture()
        val channel = TestChannel()
        var pendingCloses = 0
        val factory = object : McpStdioChannelFactory {
            override suspend fun open(server: McpServerConfig) = channel
            override suspend fun closePending(serverId: String) {
                if (++pendingCloses == 1) error("factory cleanup")
            }
        }
        val transport = McpStdioTransport(Json, McpCommandBuilder(), factory)
        transport.injectConnectionForTest(fixture.server, channel)
        try { transport.closeConnection(fixture.server.id); fail("cleanup failures lost") }
        catch (t: ResourceCleanupException) { assertEquals(2, t.failures.size) }
        transport.closeConnection(fixture.server.id)
        assertEquals(2, pendingCloses)
        assertEquals(2, channel.closes)
    }
}
