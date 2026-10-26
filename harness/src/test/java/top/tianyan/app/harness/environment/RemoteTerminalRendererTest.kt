package top.tianyan.app.harness.environment

import com.termux.terminal.TerminalSessionBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.runtime.terminal.TerminalSessionClientRouter
import top.tianyan.app.runtime.terminal.TermuxRemoteTerminalProcessFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteTerminalRendererTest {
    @Test fun `closing before renderer attachment releases remote backend without JNI`() = runBlocking {
        val backend = Backend()
        val process = TermuxRemoteTerminalProcessFactory().create(backend, "remote", TerminalSessionClientRouter())
        assertTrue(process.isAlive)
        assertNull(process.session.cwd)
        process.finish()
        assertFalse(process.isAlive)
        assertEquals(1, backend.kills)
        assertEquals(1, backend.closes)
        process.session.updateSize(80, 24)
        assertEquals(0, backend.starts)
    }
    @Test fun `failed stop remains retryable and does not report completed disposal`() = runBlocking {
        val backend = Backend().apply { failStop = true }
        val process = TermuxRemoteTerminalProcessFactory().create(backend, "remote", TerminalSessionClientRouter())
        try { process.finish(); fail("failed stop was swallowed") }
        catch (_: IllegalStateException) { }
        assertTrue(process.isAlive)
        assertEquals(0, backend.closes)
        backend.failStop = false
        process.finish()
        assertFalse(process.isAlive)
        assertEquals(2, backend.kills)
        assertEquals(1, backend.closes)
    }
    private class Backend : TerminalSessionBackend {
        var starts = 0; var kills = 0; var closes = 0; var failStop = false
        override fun start(rows: Int, columns: Int): Int { starts++; return 1 }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        override fun setSize(rows: Int, columns: Int) = Unit
        override fun waitForExit() = 0
        override fun kill() { kills++; check(!failStop) { "unconfirmed stop" } }
        override fun close() { closes++ }
    }
}
