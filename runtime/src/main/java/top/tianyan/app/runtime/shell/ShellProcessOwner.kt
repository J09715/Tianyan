package top.tianyan.app.runtime.shell

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Foreground cancellation must retain a process whose teardown failed. */
internal class ShellProcessOwner(
    val process: Process,
    private val reaper: ProcessTreeReaper = SystemProcessTreeReaper,
    private val childPid: Long? = null,
    private val forget: (ShellProcessOwner) -> Unit,
) {
    /** 直接子进程（PRoot tracer）pid；供端口回收豁免在跑的前台命令。 */
    val rootPid: Long? get() = childPid
    private val mutex = Mutex()
    private val processMonitor = Any()
    private var stopped = false
    var ownership: AutoCloseable? = null
    val readers = mutableListOf<Job>()
    private val released = BooleanArray(3)
    private val streams = listOf({ process.outputStream }, { process.inputStream }, { process.errorStream })

    fun stopProcess() = synchronized(processMonitor) {
        if (!stopped) {
            // 与长驻会话同理：必须在杀根之前快照整棵后代。强杀的 SIGKILL 不会给 PRoot tracer 收尾
            // 的机会，它的 tracee 与孙进程（安装脚本留下的后台服务、编译器守护进程）会 reparent 到
            // init 继续占用端口与文件，下一次启动就只能面对「端口已被占用」。
            val descendants = childPid?.let(reaper::descendantsOf).orEmpty()
            // 与长驻会话同理：安装/构建脚本常把后台服务放进自己的进程组，只杀根会留下整组。
            val group = childPid?.let(reaper::processGroupOf)
            if (process.isAlive) process.destroyForcibly()
            var exited = process.waitFor(1_000, TimeUnit.MILLISECONDS)
            if (!exited) {
                // 直接子进程被 SIGKILL 后仍未退出：PRoot tracer 卡死时其 tracee 与孙进程会被
                // reparent 到 init 永久存活，App 侧再无手段回收。趁根进程尚在、后代仍挂在它的
                // PPid 子树下，枚举整棵后代逐个收掉（生产路径才有 childPid）。
                group?.let { reaper.signalGroup(it, force = false) }
                childPid?.let {
                    reaper.reap(it)
                    exited = process.waitFor(1_000, TimeUnit.MILLISECONDS)
                }
                group?.let { reaper.signalGroup(it, force = true) }
            }
            reaper.reapDetached(descendants)
            check(exited) { "命令进程尚未退出（root=${childPid ?: "未知"}），保留资源供重试" }
            stopped = true
        }
    }
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            readers.forEach { it.cancel() }
            stopProcess()
            readers.joinAll()
            val failures = mutableListOf<Throwable>()
            streams.forEachIndexed { index, stream ->
                if (!released[index]) try { stream().close(); released[index] = true }
                catch (failure: Throwable) { failures += failure }
            }
            if (failures.isNotEmpty()) throw ProcessCleanupException(failures)
            ownership?.close()
            ownership = null
            forget(this@ShellProcessOwner)
        }
    }
}
