package top.tianyan.app.runtime.uml

import com.jcraft.jsch.Channel as JschChannel
import com.jcraft.jsch.ChannelShell
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.TerminalOutput
import top.tianyan.app.runtime.shell.TerminalStream

/**
 * UML guest 里的会话：把一条 SSH channel（shell 或 exec）包成 [LinuxSession]。
 *
 * 与 PRoot 侧的 [top.tianyan.app.runtime.shell.ProcessLinuxSession] 对等——
 * 上层（终端、后台进程表、MCP/ACP 协议会话）只认 LinuxSession 接口，
 * 因此 UML 与 PRoot 对它们完全透明，切换引擎不需要改任何调用方。
 *
 * - 交互终端：[UmlGuestSsh.openShellChannel]（带 PTY，可 resize）；
 * - 后台进程 / 协议会话：[UmlGuestSsh.openExecChannel]（无 PTY，按行分帧）。
 *
 * 输出走 Channel 背压缓冲：终端流不因 UI 消费慢而卡死 guest，缓冲满时丢最旧的块。
 */
class UmlSandboxSession(
    private val channel: JschChannel,
    private val input: InputStream,
    private val out: OutputStream,
    private val label: String = "uml",
) : LinuxSession {
    private val outputChannel = Channel<TerminalOutput>(OUTPUT_BUFFER_CAPACITY)
    private val closed = AtomicBoolean(false)

    private val reader = Thread({
        try {
            val buffer = ByteArray(4096)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    outputChannel.trySend(
                        TerminalOutput(TerminalStream.STDOUT, String(buffer, 0, read, Charsets.UTF_8)),
                    )
                }
            }
        } catch (_: Exception) {
            // close() 从另一线程断开 channel，读端抛 InterruptedIOException —— 视为 EOF
        } finally {
            outputChannel.close()
        }
    }, "tianyan-uml-session-$label")

    init {
        reader.isDaemon = true
        reader.start()
    }

    override val pid: Long? get() = null

    override val isAlive: Boolean get() = !closed.get() && channel.isConnected

    override val output: Flow<TerminalOutput> = outputChannel.receiveAsFlow()

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            runCatching { out.write(data); out.flush() }
        }
    }

    override suspend fun resize(columns: Int, rows: Int) {
        val shell = channel as? ChannelShell ?: return
        withContext(Dispatchers.IO) {
            runCatching { shell.setPtySize(columns, rows, columns * 8, rows * 16) }
        }
    }

    override suspend fun interrupt() {
        // Ctrl+C：PTY 会话下发 0x03；非 PTY 的 exec channel 也无害
        withContext(Dispatchers.IO) {
            runCatching { out.write(0x03); out.flush() }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            runCatching { channel.disconnect() }
        }
        runCatching { input.close() }
        outputChannel.close()
    }

    private companion object {
        const val OUTPUT_BUFFER_CAPACITY = 256
    }
}
