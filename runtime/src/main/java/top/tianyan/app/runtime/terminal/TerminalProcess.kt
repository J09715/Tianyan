package top.tianyan.app.runtime.terminal

import android.util.Log
import com.termux.terminal.TerminalSession
import top.tianyan.app.runtime.shell.InteractiveLaunchSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Native rendering remains explicit; tests can use a process without starting a PTY. */
interface TerminalProcess {
    val session: TerminalSession
    val isAlive: Boolean
    suspend fun finish()
    fun write(data: ByteArray)
}

fun interface TerminalProcessFactory {
    fun create(launch: InteractiveLaunchSpec, client: TerminalSessionClientRouter): TerminalProcess
}

class TermuxTerminalProcessFactory : TerminalProcessFactory {
    override fun create(launch: InteractiveLaunchSpec, client: TerminalSessionClientRouter): TerminalProcess {
        Log.i("TianyanTerminal", "createSession cwd=${launch.workingDirectory} argv0=${launch.executable} " +
            "argc=${launch.arguments.size} env=${launch.environment.size}")
        val terminal = TerminalSession(launch.executable, launch.workingDirectory, launch.arguments,
            launch.environment, 2000, client)
        return object : TerminalProcess {
            override val session = terminal
            override val isAlive get() = terminal.isRunning
            override suspend fun finish() {
                terminal.finishIfRunning()
                check(withTimeoutOrNull(5_000) { while (terminal.isRunning) delay(10); true } == true) {
                    "终端进程尚未退出，保留资源供重试"
                }
            }
            override fun write(data: ByteArray) = terminal.write(data, 0, data.size)
        }
    }
}
