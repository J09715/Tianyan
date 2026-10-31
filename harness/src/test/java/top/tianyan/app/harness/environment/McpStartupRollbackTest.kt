package top.tianyan.app.harness.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.core.ResourceCleanupException
import top.tianyan.app.harness.core.SessionResourceScope
import top.tianyan.app.harness.mcp.*
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext

@OptIn(ExperimentalCoroutinesApi::class)
class McpStartupRollbackTest {
    @Test fun `closed session rejects ownership before starting any subprocess`() = runTest {
        val fixture = McpStartupFixture()
        val world = fixture.World()
        val owner = SessionResourceScope().also { it.close() }
        try {
            withContext(ExecutionEnvironmentContext(world, owner::own)) { fixture.factory.open(fixture.server) }
            fail("closed owner accepted")
        } catch (_: IllegalStateException) { }
        fixture.factory.closePending(fixture.server.id)
        assertEquals(0, world.opens)
        assertTrue(fixture.worlds.isEmpty())
    }

    @Test fun `cancelled environment startup retains failed late rollback for explicit invalidation`() = runTest {
        val fixture = McpStartupFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeEnvironment = { world ->
            world.closeFailures = 1
            withContext(NonCancellable) { gate.await() }
        }
        val creating = async { fixture.factory.open(fixture.server) }
        runCurrent(); creating.cancel(); gate.complete(Unit); creating.join()
        val world = fixture.worlds.single()
        assertEquals(0, world.opens)
        assertEquals(1, world.closes)
        fixture.factory.closePending(fixture.server.id)
        fixture.factory.closePending(fixture.server.id)
        assertEquals(2, world.closes)
    }

    @Test fun `cancelled subprocess startup retains both cleanup failures without closing either twice after success`() = runTest {
        val fixture = McpStartupFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeSession = { world ->
            world.closeFailures = 1; world.session.closeFailures = 1
            withContext(NonCancellable) { gate.await() }
        }
        val creating = async { fixture.factory.open(fixture.server) }
        runCurrent(); creating.cancel(); gate.complete(Unit); creating.join()
        val world = fixture.worlds.single()
        assertEquals(1, world.session.closes)
        assertEquals(1, world.closes)
        fixture.factory.closePending(fixture.server.id)
        fixture.factory.closePending(fixture.server.id)
        assertEquals(2, world.session.closes)
        assertEquals(2, world.closes)
    }

    @Test fun `session disposal retries failed startup without closing the borrowed environment`() = runTest {
        val fixture = McpStartupFixture()
        val world = fixture.World()
        val owner = SessionResourceScope()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeSession = {
            world.session.closeFailures = 2
            withContext(NonCancellable) { gate.await() }
        }
        val creating = async { owner.activity {
            withContext(ExecutionEnvironmentContext(world, owner::own)) { fixture.factory.open(fixture.server) }
        } }
        runCurrent()
        val closing = async { runCatching { owner.close() }.exceptionOrNull() }
        runCurrent(); gate.complete(Unit)
        assertTrue(closing.await() is ResourceCleanupException)
        creating.join(); owner.close(); fixture.factory.closePending(fixture.server.id)
        assertEquals(3, world.session.closes)
        assertEquals(0, world.closes)
    }

    @Test fun `reconnect cannot allocate a new world until old rollback succeeds`() = runTest {
        val fixture = McpStartupFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeEnvironment = { world ->
            world.closeFailures = 2
            withContext(NonCancellable) { gate.await() }
        }
        val creating = async { fixture.factory.open(fixture.server) }
        runCurrent(); creating.cancel(); gate.complete(Unit); creating.join()
        fixture.beforeEnvironment = {}
        try { fixture.factory.open(fixture.server); fail("replacement bypassed cleanup") }
        catch (_: ResourceCleanupException) { }
        assertEquals(1, fixture.worlds.size)
        val channel = fixture.factory.open(fixture.server)
        assertEquals(3, fixture.worlds[0].closes)
        assertEquals(2, fixture.worlds.size)
        channel.close(); fixture.factory.closePending(fixture.server.id)
        assertEquals(1, fixture.worlds[1].session.closes)
        assertEquals(1, fixture.worlds[1].closes)
    }

    @Test fun `invalidation cancels and drains environment creation before returning`() = runTest {
        val fixture = McpStartupFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeEnvironment = { withContext(NonCancellable) { gate.await() } }
        val creating = async { fixture.factory.open(fixture.server) }
        runCurrent()
        val closing = async { fixture.factory.closePending(fixture.server.id) }
        runCurrent(); assertFalse(closing.isCompleted)
        gate.complete(Unit); closing.await(); creating.join()
        assertTrue(creating.isCancelled)
        assertEquals(0, fixture.worlds.single().opens)
        assertEquals(1, fixture.worlds.single().closes)
    }

    @Test fun `transport invalidation retries startup rollback even when no connection was returned`() = runTest {
        val fixture = McpStartupFixture()
        fixture.beforeSession = { it.closeFailures = 2; error("startup failed") }
        val transport = McpStdioTransport(Json, McpCommandBuilder(), fixture.factory)
        assertFalse(transport.check(fixture.server).online)
        try { transport.closeConnection(fixture.server.id); fail("cleanup failure lost") }
        catch (_: ResourceCleanupException) { }
        transport.closeConnection(fixture.server.id)
        assertEquals(1, fixture.worlds.size)
        assertEquals(3, fixture.worlds.single().closes)
    }

    @Test fun `same server uses separate startup ownership across bound execution worlds`() = runTest {
        val fixture = McpStartupFixture()
        val one = fixture.World("one")
        val two = fixture.World("two")
        val firstOwner = SessionResourceScope()
        val secondOwner = SessionResourceScope()
        var firstDetaches = 0
        val first = withContext(ExecutionEnvironmentContext(one) { release ->
            val registration = firstOwner.own(release)
            AutoCloseable { firstDetaches++; registration.close() }
        }) { fixture.factory.open(fixture.server) }
        val second = withContext(ExecutionEnvironmentContext(two, secondOwner::own)) { fixture.factory.open(fixture.server) }
        assertTrue(first.isAlive)
        assertTrue(second.isAlive)
        firstOwner.close()
        assertFalse(first.isAlive)
        assertTrue(second.isAlive)
        secondOwner.close()
        assertEquals(1, firstDetaches)
        assertEquals(0, one.closes)
        assertEquals(0, two.closes)
    }

    @Test fun `startup callback cannot invalidate its own acquisition even in non cancellable context`() = runTest {
        val fixture = McpStartupFixture()
        fixture.beforeEnvironment = { withContext(NonCancellable) {
            try { fixture.factory.closePending(fixture.server.id); fail("self join accepted") }
            catch (t: ResourceCleanupException) { assertTrue(t.failures.single().message.orEmpty().contains("own creation")) }
        } }
        val channel = fixture.factory.open(fixture.server)
        assertTrue(channel.isAlive)
        fixture.factory.closePending(fixture.server.id)
        assertEquals(1, fixture.worlds.single().session.closes)
        assertEquals(1, fixture.worlds.single().closes)
    }

    @Test fun `startup error stays primary when environment rollback also fails`() = runTest {
        val fixture = McpStartupFixture()
        fixture.beforeSession = { it.closeFailures = 1; throw IllegalArgumentException("bad server") }
        try { fixture.factory.open(fixture.server); fail("startup failure lost") }
        catch (t: IllegalArgumentException) {
            assertEquals("bad server", t.message)
            assertTrue(generateSequence(t as Throwable) { it.cause }.any { it.suppressed.isNotEmpty() })
        }
        fixture.factory.closePending(fixture.server.id)
        assertEquals(2, fixture.worlds.single().closes)
    }
}
