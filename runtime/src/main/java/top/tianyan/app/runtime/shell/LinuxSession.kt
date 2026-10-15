package top.tianyan.app.runtime.shell

import kotlinx.coroutines.flow.Flow

data class TerminalOutput(
    val stream: TerminalStream,
    val text: String,
)

enum class TerminalStream {
    STDOUT,
    STDERR,
    SYSTEM,
}

data class SessionConfig(
    val columns: Int = 80,
    val rows: Int = 24,
    val workingDirectory: String = "/root",
    val environment: Map<String, String> = emptyMap(),
    val commandLine: String = "/bin/bash -i",
    val allowSttyResize: Boolean = commandLine == "/bin/bash -i",
    /** 终端会话进入时打印 TIANYAN 横幅；MCP STDIO 等协议会话必须保持 false 以免污染输出流。 */
    val showBanner: Boolean = false,
    /**
     * 协议会话开关：关闭 PTY 行规程（ECHO/ICANON/ISIG/OPOST…）。
     *
     * 交互终端必须保持 false —— 用户要看见自己敲的字、要 Ctrl+C。
     * ACP / MCP STDIO 这类「按行分帧的 JSON 协议」必须为 true，否则宿主写出的
     * 请求帧会被内核回显读回，被读循环误判成引擎的反向请求，握手直接失败。
     */
    val rawTerminal: Boolean = false,
)

interface LinuxSession {
    val pid: Long? get() = null
    val isAlive: Boolean
    val output: Flow<TerminalOutput>

    suspend fun write(data: ByteArray)

    suspend fun resize(columns: Int, rows: Int)

    suspend fun interrupt()

    suspend fun close()
}
