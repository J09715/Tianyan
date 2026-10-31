package top.tianyan.app.harness.environment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalDisposalBoundaryTest {
    @Test fun `environment is not closed before asynchronous process finish drains`() = runTest {
        val fixture = TerminalLifecycleFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.construct = { process -> process.beforeFinish = { entered.complete(Unit); release.await() } }
        fixture.manager.createSession(id = "terminal")
        val closing = async { fixture.manager.closeAllSessions() }
        runCurrent()
        assertTrue(entered.isCompleted)
        assertFalse(closing.isCompleted)
        assertTrue(fixture.closeAttempts.isEmpty())
        release.complete(Unit)
        closing.await()
        assertEquals(1, fixture.closeAttempts["terminal"])
    }
    @Test fun `environment callback cannot recursively close its terminal`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.manager.createSession(id = "terminal")
        fixture.beforeClose = { fixture.manager.closeSession(it) }
        assertNotNull(runCatching { fixture.manager.closeAllSessions() }.exceptionOrNull())
        assertEquals(1, fixture.manager.handles.value.size)
        fixture.beforeClose = {}
        fixture.manager.closeAllSessions()
        assertEquals(1, fixture.processes.single().finishes)
        assertEquals(2, fixture.closeAttempts["terminal"])
    }
    @Test fun `environment callback cannot recursively close all terminals`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.manager.createSession(id = "terminal")
        fixture.beforeClose = { fixture.manager.closeAllSessions() }
        assertNotNull(runCatching { fixture.manager.closeAllSessions() }.exceptionOrNull())
        fixture.beforeClose = {}
        fixture.manager.closeAllSessions()
        assertTrue(fixture.manager.handles.value.isEmpty())
    }
    @Test fun `creation rollback callback cannot wait for its own creation`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.construct = { process -> process.beforeFinish = { fixture.manager.closeSession("terminal") } }
        fixture.repository.afterUpsert = { error("create failed") }
        val failure = runCatching { fixture.manager.createSession(id = "terminal") }.exceptionOrNull()!!
        assertEquals("create failed", failure.message)
        assertTrue(failure.suppressed.isNotEmpty() || failure.cause?.suppressed?.isNotEmpty() == true)
        fixture.processes.single().beforeFinish = {}
        fixture.manager.closeAllSessions()
        assertTrue(fixture.manager.handles.value.isEmpty())
    }
}
