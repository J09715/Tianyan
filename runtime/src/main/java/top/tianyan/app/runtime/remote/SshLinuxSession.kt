package top.tianyan.app.runtime.remote

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import top.tianyan.app.runtime.shell.*
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Streams are drained separately; closing a channel unblocks both readers. */
internal class SshLinuxSession private constructor(private val channel: ChannelExec) : LinuxSession {
    private val stdout = channel.inputStream
    private val stderr = channel.errStream
    private val input = channel.outputStream
    override val isAlive get() = channel.isConnected && !channel.isClosed
    val exitCode get() = channel.exitStatus
    /** PTY renderer consumes bytes directly, preserving escape sequences and split UTF-8. */
    val terminalBytes: Flow<ByteArray> = channelFlow {
        suspend fun read(stream: InputStream) {
            val buffer = ByteArray(4096)
            while (true) {
                val size = runInterruptible(Dispatchers.IO) { stream.read(buffer) }
                if (size < 0) break
                if (size > 0) send(buffer.copyOf(size))
            }
        }
        launch { read(stdout) }
        launch { read(stderr) }
    }
    override val output: Flow<TerminalOutput> = channelFlow {
        suspend fun read(stream: InputStream, kind: TerminalStream) {
            val reader = stream.reader(StandardCharsets.UTF_8)
            val buffer = CharArray(4096)
            while (true) {
                val size = try { runInterruptible(Dispatchers.IO) { reader.read(buffer) } }
                catch (e: java.io.IOException) { currentCoroutineContext().ensureActive(); throw e }
                if (size < 0) break
                send(TerminalOutput(kind, String(buffer, 0, size)))
            }
        }
        launch { read(stdout, TerminalStream.STDOUT) }
        launch { read(stderr, TerminalStream.STDERR) }
    }
    override suspend fun write(data: ByteArray) = runInterruptible(Dispatchers.IO) {
        synchronized(input) { input.write(data); input.flush() }
    }
    override suspend fun resize(columns: Int, rows: Int) { channel.setPtySize(columns, rows, 0, 0) }
    override suspend fun interrupt() { channel.sendSignal("INT") }
    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (isAlive) {
            channel.sendSignal("TERM")
            repeat(30) { if (isAlive) delay(100) }
            if (isAlive) {
                channel.sendSignal("KILL")
                repeat(20) { if (isAlive) delay(100) }
            }
            check(!isAlive) { "服务器未确认远程进程退出，保留连接以便重试停止" }
        }
        channel.disconnect()
    }

    companion object {
        suspend fun open(session: Session, config: SessionConfig, pty: Boolean, protocolEnvironment: Boolean = false): SshLinuxSession =
            withContext(Dispatchers.IO) {
                val channel = session.openChannel("exec") as ChannelExec
                try {
                    channel.setPty(pty)
                    if (pty) { channel.setPtyType("xterm-256color"); channel.setPtySize(config.columns, config.rows, 0, 0) }
                    val exported = if (protocolEnvironment) emptyMap() else config.environment
                    if (protocolEnvironment) config.environment.forEach { (key, value) -> channel.setEnv(key, value) }
                    channel.setCommand(remoteCommand(config.commandLine, config.workingDirectory, exported))
                    val process = SshLinuxSession(channel)
                    runInterruptible { channel.connect(15_000) }
                    currentCoroutineContext().ensureActive()
                    process
                } catch (t: Throwable) { channel.disconnect(); throw t }
            }

        /**
         * JSch's env request sets want_reply=false, so a server that rejects setEnv still accepts exec
         * and simply omits the variable. Confirm the probe value is visible, with no profile sourced.
         */
        suspend fun protocolEnvironmentAccepted(session: Session): Boolean {
            val opened = openProbe(session) ?: return false
            return try {
                val stdout = StringBuilder()
                withTimeout(5_000) {
                    opened.output.collect { output ->
                        if (output.stream == TerminalStream.STDOUT && stdout.length < 16) stdout.append(output.text)
                    }
                }
                stdout.toString() == SSH_ENV_PROBE_VALUE
            } catch (_: TimeoutCancellationException) {
                false
            } finally {
                withContext(NonCancellable) { runCatching { opened.close() } }
            }
        }

        private suspend fun openProbe(session: Session): SshLinuxSession? {
            if (!session.isUsable()) return null
            val channel = session.openChannel("exec") as ChannelExec
            return try {
                channel.setEnv(SSH_ENV_PROBE_KEY, SSH_ENV_PROBE_VALUE)
                channel.setCommand(SSH_ENV_PROBE_COMMAND)
                val created = SshLinuxSession(channel)
                runInterruptible { channel.connect(15_000) }
                currentCoroutineContext().ensureActive()
                created
            } catch (t: Throwable) {
                runCatching { channel.disconnect() }
                if (t is CancellationException) throw t
                if (!session.isUsable()) throw t
                null
            }
        }
    }
}
