package top.tianyan.app.runtime.pty

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.runtime.GuestProcessEnvironment
import top.tianyan.app.runtime.shell.*

/** JNI master I/O and child reaping remain owned until every shutdown step succeeds. */
class NativePtySession internal constructor(
    private val masterFd: Int,
    private val childPid: Int,
    private val backend: NativePtyBackend,
    private val cleanupCallback: suspend () -> Unit = {},
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val shutdownTimeoutMs: Long = 500,
) : LinuxSession {
    constructor(command: List<String>, hostEnvironment: Map<String, String>, config: SessionConfig,
        cleanupCallback: suspend () -> Unit = {}) : this(
        NativePty.openAndExec(
            command.toTypedArray(),
            GuestProcessEnvironment.commandLaunchEnvironment(
                hostEnvironment,
                command.firstOrNull().orEmpty(),
                System.getenv("PATH"),
            ).entries.map { "${it.key}=${it.value}" }.toTypedArray(),
            "/",
            config.columns,
            config.rows,
        ), cleanupCallback,
    )
    private constructor(pair: IntArray, cleanup: suspend () -> Unit) :
        this(pair[0], pair[1], JniNativePtyBackend, cleanup)

    private val child = NativePtyChild(backend, childPid)
    private val closing = AtomicBoolean(false)
    private val closeMutex = Mutex()
    private val inputMutex = Mutex()
    private var fdReleased = false
    private var cleanupReleased = false
    private val outputChannel = Channel<TerminalOutput>(OUTPUT_BUFFER_CAPACITY)
    private val sessionScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob(scope.coroutineContext[Job]))
    private val decoder = IncrementalUtf8Decoder(8192)
    private val readerJob = sessionScope.launch {
        val buffer = ByteArray(8192)
        try {
            while (!closing.get()) {
                currentCoroutineContext().ensureActive()
                val length = backend.read(masterFd, buffer)
                if (length < 0) break
                if (length > 0) decoder.decode(buffer, length).takeIf { it.isNotEmpty() }?.let {
                    outputChannel.send(TerminalOutput(TerminalStream.STDOUT, it))
                }
                if (length == 0) yield()
            }
            if (!closing.get()) decoder.finish().takeIf { it.isNotEmpty() }?.let {
                outputChannel.send(TerminalOutput(TerminalStream.STDOUT, it))
            }
            // EOF can precede child exit; poll without preventing close from signalling it.
            while (!closing.get() && child.isAlive) delay(10)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            // A terminated slave or a concurrently closing master ends the output stream.
        } finally { outputChannel.close() }
    }.also { it.invokeOnCompletion { outputChannel.close() } }

    override val output: Flow<TerminalOutput> = outputChannel.receiveAsFlow()
    override val pid: Long get() = childPid.toLong()
    override val isAlive: Boolean get() = child.isAlive

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        inputMutex.withLock {
            var offset = 0
            while (!closing.get() && offset < data.size) {
                currentCoroutineContext().ensureActive()
                val written = backend.write(masterFd, data, offset, data.size - offset)
                if (written < 0) throw IOException("PTY write failed")
                offset += written
                if (written == 0) yield()
            }
        }
    }
    override suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        inputMutex.withLock { if (!closing.get()) backend.resize(masterFd, columns.coerceIn(20, 400), rows.coerceIn(5, 200)) }
    }
    override suspend fun interrupt() = write(byteArrayOf(3))

    override suspend fun close() {
        val active = currentCoroutineContext()[ClosingSessions]?.sessions.orEmpty()
        check(this !in active) { "PTY 清理不能递归关闭所属会话" }
        withContext(NonCancellable + Dispatchers.IO + ClosingSessions(active + this)) {
            closeMutex.withLock {
                closing.set(true)
                readerJob.cancel()
                sessionScope.cancel()
                outputChannel.close()
                val failures = mutableListOf<Throwable>()
                var stopped = false
                try { child.stop(shutdownTimeoutMs); stopped = true } catch (failure: Throwable) { failures += failure }
                // Native read/write polls are bounded, even if signalling failed.
                readerJob.join()
                inputMutex.withLock {
                    if (!fdReleased) try { backend.close(masterFd); fdReleased = true }
                    catch (failure: Throwable) { failures += failure }
                }
                if (stopped && !cleanupReleased) try { cleanupCallback(); cleanupReleased = true }
                catch (failure: Throwable) { failures += failure }
                if (failures.isNotEmpty()) throw ProcessCleanupException(failures)
            }
        }
    }
    private class ClosingSessions(val sessions: Set<NativePtySession>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ClosingSessions>
    }
    companion object { const val OUTPUT_BUFFER_CAPACITY = 1024 }
}
