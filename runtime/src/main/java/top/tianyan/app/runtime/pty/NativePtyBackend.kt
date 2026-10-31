package top.tianyan.app.runtime.pty

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Every call is bounded; failed close must leave the descriptor owned by the caller. */
internal interface NativePtyBackend {
    fun read(fd: Int, buffer: ByteArray): Int
    fun write(fd: Int, data: ByteArray, offset: Int, length: Int): Int
    fun resize(fd: Int, columns: Int, rows: Int)
    fun signal(pid: Int, signal: Int)
    fun pollExit(pid: Int): Boolean
    fun close(fd: Int)
}

internal object JniNativePtyBackend : NativePtyBackend {
    override fun read(fd: Int, buffer: ByteArray) = NativePty.readFd(fd, buffer)
    override fun write(fd: Int, data: ByteArray, offset: Int, length: Int) = NativePty.writeFd(fd, data, offset, length)
    override fun resize(fd: Int, columns: Int, rows: Int) {
        if (NativePty.resizeFd(fd, columns, rows) != 0) throw IOException("PTY resize failed")
    }
    override fun signal(pid: Int, signal: Int) {
        if (NativePty.killPid(pid, signal) != 0) throw IOException("PTY signal $signal failed")
    }
    override fun pollExit(pid: Int) = NativePty.pollExit(pid)
    // On Linux close consumes the fd even on EINTR; retrying could close a reused descriptor.
    override fun close(fd: Int) = NativePty.closeFd(fd)
}

/** Reaping and signalling share a short lock, so a reaped/reused PID is never signalled. */
internal class NativePtyChild(private val backend: NativePtyBackend, private val pid: Int) {
    private val monitor = Any()
    private var reaped = false
    val isAlive: Boolean get() = synchronized(monitor) { !reap() }
    private fun reap(): Boolean {
        if (!reaped) reaped = backend.pollExit(pid)
        return reaped
    }
    private fun signal(signal: Int) = synchronized(monitor) {
        if (!reap()) try { backend.signal(pid, signal) } catch (failure: Throwable) {
            // Exiting between the probe and kill is a successful stop, not a signal failure.
            if (!reap()) throw failure
        }
    }
    suspend fun stop(timeoutMs: Long) {
        signal(1)
        if (!awaitExit(timeoutMs)) {
            signal(9)
            check(awaitExit(timeoutMs)) { "PTY 进程强制终止后仍未退出，保留会话供重试" }
        }
    }
    private suspend fun awaitExit(timeoutMs: Long): Boolean {
        withTimeoutOrNull(timeoutMs) { while (isAlive) delay(10) }
        return !isAlive
    }
}
