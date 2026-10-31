package top.tianyan.app.runtime.shell

import top.tianyan.app.runtime.GuestProcessEnvironment
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 无 JNI 的子进程 backend，通过管道读写 stdin/stdout。
 *
 * 协议会话直接运行 PRoot 命令；兼容终端由调用方通过 Debian `script` 创建 PTY，
 * 并提供窗口调整回调。两种模式共用 LinuxSession 的输出与清理生命周期。
 */
class ProcessLinuxSession internal constructor(
    private val process: Process,
    private val allowSttyResize: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val resizeCallback: (suspend (columns: Int, rows: Int) -> Unit)? = null,
    private val cleanupCallback: suspend () -> Unit = {},
    private val reaper: ProcessTreeReaper = SystemProcessTreeReaper,
    private val childPid: Long? = null,
) : LinuxSession {
    private constructor(
        forked: Forked,
        allowSttyResize: Boolean,
        scope: CoroutineScope,
        resizeCallback: (suspend (columns: Int, rows: Int) -> Unit)?,
        cleanupCallback: suspend () -> Unit,
    ) : this(
        forked.process, allowSttyResize, scope, resizeCallback, cleanupCallback, forked.reaper, forked.childPid,
    )

    constructor(
        command: List<String>,
        hostEnvironment: Map<String, String> = emptyMap(),
        allowSttyResize: Boolean = true,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        resizeCallback: (suspend (columns: Int, rows: Int) -> Unit)? = null,
        cleanupCallback: suspend () -> Unit = {},
    ) : this(Forked(command, hostEnvironment, scope), allowSttyResize, scope, resizeCallback, cleanupCallback)

    private val outputChannel = Channel<TerminalOutput>(OUTPUT_BUFFER_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val closeMutex = Mutex()
    private var processStopped = false
    private val released = BooleanArray(4)
    private val releaseSteps: List<suspend () -> Unit> = listOf(
        { process.outputStream.close() },
        { process.inputStream.close() },
        { process.errorStream.close() },
        { cleanupCallback() },
    )
    private val sessionScope = CoroutineScope(
        scope.coroutineContext.minusKey(Job) + SupervisorJob(scope.coroutineContext[Job]),
    )
    private val readerJob: Job = sessionScope.launch {
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { input ->
                val buffer = CharArray(4096)
                while (!closed.get()) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        outputChannel.send(
                            TerminalOutput(
                                TerminalStream.STDOUT,
                                String(buffer, 0, read),
                            ),
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            // close()/process.destroy() closes the stream from another thread while
            // this coroutine is blocked in read(); Android surfaces that as
            // InterruptedIOException. Treat it as EOF instead of crashing the app.
        } finally { outputChannel.close() }
    }.also { it.invokeOnCompletion { outputChannel.close() } }

    override val output: Flow<TerminalOutput> = outputChannel.receiveAsFlow()

    // Android 的 Process API 不保证能拿到任意实现的 pid；这里只回报告诉我们自己从
    // /proc 快照到的直接子进程 pid（非 Linux / 权限受限时仍为 null）。
    override val pid: Long? get() = childPid

    override val isAlive: Boolean
        get() = !closed.get() && process.isAlive

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (!closed.get()) {
            process.outputStream.write(data)
            process.outputStream.flush()
        }
    }

    override suspend fun resize(columns: Int, rows: Int) {
        if (closed.get()) return
        // The Script backend records the PTY slave path and updates it through a
        // separate Runtime command. This avoids injecting `stty` into a TUI's
        // stdin while keeping the LinuxSession contract backend-independent.
        if (resizeCallback != null) {
            resizeCallback.invoke(columns, rows)
        } else if (allowSttyResize) {
            write("stty cols $columns rows $rows\n".toByteArray())
        }
    }

    override suspend fun interrupt() = write(byteArrayOf(3))

    override suspend fun close() {
        val closing = currentCoroutineContext()[ClosingSessions]?.sessions.orEmpty()
        check(this !in closing) { "进程清理回调不能再次关闭所属会话" }
        withContext(NonCancellable + Dispatchers.IO + ClosingSessions(closing + this)) {
            closeMutex.withLock {
                closed.set(true)
                readerJob.cancel()
                sessionScope.cancel()
                outputChannel.close()
                // A blocking stream read needs the process to exit before it can be joined.
                if (!processStopped) {
                    stopProcess()
                    processStopped = true
                }
                readerJob.join()
                val failures = mutableListOf<Throwable>()
                releaseSteps.forEachIndexed { index, release ->
                    if (!released[index]) {
                        try { release(); released[index] = true }
                        catch (failure: Throwable) { failures += failure }
                    }
                }
                if (failures.isNotEmpty()) throw ProcessCleanupException(failures)
            }
        }
    }

    private fun stopProcess() {
        // 客态网关（node/python 等）是 PRoot tracer 的孙进程。根进程一旦先死，它们会被 reparent
        // 到 init：继续持有 LISTEN 套接字、App 侧却再无记录，下一次启动只能看到「端口已被占用」。
        // 因此必须在动手杀之前把整棵后代快照下来，收尾后统一补刀。
        val descendants = childPid?.let(reaper::descendantsOf).orEmpty()
        // 网关常自己 setsid/setpgid（host 与 server 同组）：只杀根会把整组留给 init，所以先拿到
        // 进程组；与宿主 App 同组时返回 null（整组信号会连带杀掉自己）。
        val group = childPid?.let(reaper::processGroupOf)
        if (process.isAlive) process.destroy()
        var exited = process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!exited) {
            group?.let { reaper.signalGroup(it, force = false) }
            process.destroyForcibly()
            exited = process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exited && childPid != null) {
                // 根进程被强杀后仍未退出：PTY/PRoot 会话的 tracee 与 `bash -l` 子进程会被 reparent
                // 到 init 永久阻塞在 do_select/do_wait，App 侧再无手段回收。趁根系尚在，枚举整棵
                // 后代逐个收掉，避免孤儿进程组累积，然后再确认一次根进程是否退出。
                reaper.reap(childPid)
                exited = process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            group?.let { reaper.signalGroup(it, force = true) }
        }
        // 优雅退出（PRoot --kill-on-exit）时快照里的进程多半已经消失，kill 失败即静默跳过；
        // 但 tracer 未替我们收尾的那些（网关子进程、脚本孙进程）只能在这里被收掉。
        reaper.reapDetached(descendants)
        check(exited) { "进程强制终止后仍未退出（root=${childPid ?: "未知"}），保留会话供重试" }
    }

    private class ClosingSessions(val sessions: Set<ProcessLinuxSession>) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ClosingSessions>
    }

    /** 生产路径的 fork 结果：一并带回「直接子进程 pid」，供收尾失败时回收其后代。 */
    private class Forked(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        scope: CoroutineScope,
    ) {
        val reaper: ProcessTreeReaper = SystemProcessTreeReaper
        val process: Process
        val childPid: Long?

        init {
            scope.coroutineContext.ensureActive()
            val launchEnvironment = GuestProcessEnvironment.commandLaunchEnvironment(
                hostEnvironment,
                command.firstOrNull().orEmpty(),
                System.getenv("PATH"),
            )
            // 取样 tid 与 fork 必须在同一线程且之间没有挂起点：/proc 以线程粒度记录 children，
            // 一旦期间线程被调度走，解析出的 pid 就不再是本次 fork 的子进程。
            val tid = reaper.forkTid()
            process = ProcessBuilder(command).directory(File("/"))
                .apply { environment().clear(); environment().putAll(launchEnvironment) }
                .redirectErrorStream(true).start()
            childPid = tid?.let(reaper::childOfThread)
        }
    }

    private companion object {
        const val PROCESS_SHUTDOWN_TIMEOUT_MS = 500L
        const val OUTPUT_BUFFER_CAPACITY = 1024
    }
}
