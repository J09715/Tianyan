package top.tianyan.app.runtime.shell

import top.tianyan.app.runtime.RuntimePathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import top.tianyan.app.runtime.closeRuntimeResources
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext
import kotlinx.coroutines.currentCoroutineContext

class ProcessShellExecutor internal constructor(
    private val baseEnvironment: () -> Map<String, String>,
    private val captureDirectory: () -> File = { File(System.getProperty("java.io.tmpdir"), "tianyan-command-output") },
    private val reaper: ProcessTreeReaper = SystemProcessTreeReaper,
    private val startProcess: (List<String>, File?, Map<String, String>) -> Process,
) : ShellExecutor {
    constructor(pathManager: RuntimePathManager) : this({ pathManager.hostProcessEnvironment() }, { pathManager.commandOutputCacheDir }, startProcess = { command, cwd, env ->
        ProcessBuilder(command).apply {
            cwd?.let { directory(it) }
            environment().clear()
            environment().putAll(env)
        }.redirectErrorStream(false).start()
    })
    private val pending = ConcurrentHashMap<ShellProcessOwner, Boolean>()
    override fun hasPending() = pending.isNotEmpty()
    override fun hasUnsettled() = pending.values.any { it }
    override suspend fun closePending() = closeRuntimeResources(pending.keys.toList().map { owner -> suspend { owner.close() } })

    override suspend fun execute(
        command: List<String>,
        workingDirectory: File?,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: ((String) -> Unit)?,
    ): CommandResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var acquired: ShellProcessOwner? = null
        val capture = currentCoroutineContext()[CommandOutputCapture]?.start(captureDirectory())
        val registration = currentCoroutineContext()[ExecutionEnvironmentContext]?.own { acquired?.close() }
        // forkTid 必须取样于 startProcess 之前，且两者之间不得有挂起点：/proc 以线程粒度记录
        // children，一旦期间线程被调度走，解析出的 pid 就不再是本次 fork 的子进程。
        val forkTid = reaper.forkTid()
        val process = try { startProcess(command, workingDirectory, baseEnvironment() + environment) }
        catch (failure: Throwable) { registration?.close(); throw failure }
        val childPid = forkTid?.let(reaper::childOfThread)
        val owned = ShellProcessOwner(process = process, reaper = reaper, childPid = childPid) { pending.remove(it) }
        acquired = owned
        owned.ownership = registration
        pending[owned] = false

        // 🌟 关键修复：主动关闭子进程的标准输入（stdin），防止任何交互式脚本/命令无限阻塞在等待键盘输入上
        runCatching { process.outputStream.close() }

        val stdoutDeferred = async(Dispatchers.IO) {
            runInterruptible { readFully(process.inputStream, onOutput, capture?.first) }
        }.also { owned.readers += it }
        val stderrDeferred = async(Dispatchers.IO) {
            runInterruptible { readFully(process.errorStream, onOutput, capture?.second) }
        }.also { owned.readers += it }

        var primaryFailure: Throwable? = null
        try {
            val exitCode = withTimeout(timeoutMs) {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            }
            // 成功路径同样必须给排水加超时：`sh -c 'server &'` 这类命令会让孙进程
            // 继承 stdout/stderr 管道写端，主进程退出后 EOF 永不到来，awaitAll 无限
            // 挂起、Agent 卡死。宽限期内 EOF 正常到达则返回完整输出；超时则取消读取
            // 协程（runInterruptible 将取消映射为线程中断，阻塞 read 抛
            // InterruptedIOException 被 readFully 兜底为 EOF），保留已读输出。
            // 不取消的话 withContext 退出前 join 阻塞中的 reader 仍会永久挂起。
            val drained = withTimeoutOrNull(OUTPUT_DRAIN_TIMEOUT_MS) {
                listOf(stdoutDeferred, stderrDeferred).awaitAll()
            }
            if (drained == null) {
                stdoutDeferred.cancel()
                stderrDeferred.cancel()
            }
            CommandResult(
                exitCode = exitCode,
                stdout = drained?.get(0).orEmpty(),
                stderr = drained?.get(1).orEmpty(),
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (timeout: TimeoutCancellationException) {
            try {
                owned.stopProcess()
                // 等进程树真正消亡：PRoot 被强杀后，被 ptrace 的 npm/node 由内核
                // 异步清除，若立刻开始回滚删除目录，可能撞上仍在写入的残留进程。
                // Preserve output produced before the timeout. Cancelling the
                // readers here discarded the only useful diagnostic from long
                // setup scripts (APT/Gradle/Flutter), leaving just the timeout
                // sentence in the installation dialog.
                val partialStdout = runCatching {
                    withTimeoutOrNull(PROCESS_TEARDOWN_TIMEOUT_MS) { stdoutDeferred.await() }.orEmpty()
                }.getOrDefault("")
                val partialStderr = runCatching {
                    withTimeoutOrNull(PROCESS_TEARDOWN_TIMEOUT_MS) { stderrDeferred.await() }.orEmpty()
                }.getOrDefault("")
                // 读取协程可能仍阻塞在孙进程持有的管道上：中断并放弃，否则 withContext
                // 退出前 join 它们会无限挂起。
                stdoutDeferred.cancel()
                stderrDeferred.cancel()
                CommandResult(
                    exitCode = TIMEOUT_EXIT_CODE,
                    stdout = partialStdout,
                    stderr = buildString {
                        if (partialStderr.isNotBlank()) {
                            append(partialStderr.trimEnd())
                            append('\n')
                        }
                        append("Command timed out after ${timeoutMs}ms")
                    },
                    durationMs = System.currentTimeMillis() - startedAt,
                    outputDiagnostic = "Command timed out after ${timeoutMs}ms",
                )
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            }
        } catch (cancellation: CancellationException) {
            // 用户主动取消编译：强杀 PRoot 进程树，避免 Gradle 后台继续跑
            primaryFailure = cancellation
            throw cancellation
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { owned.close() } catch (cleanup: Throwable) {
                pending.computeIfPresent(owned) { _, _ -> true }
                val primary = primaryFailure
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    /**
     * 排水读取整个流。必须保持非 suspend 且运行在 [runInterruptible] 内：
     * 管道可能被未退出的孙进程持有导致 EOF 永不到来，只有线程中断才能
     * 打破阻塞中的 `read()`（InterruptedIOException，视为 EOF 兜底）。
     */
    private fun readFully(
        stream: InputStream,
        onOutput: ((String) -> Unit)? = null,
        capture: CommandStreamCapture? = null,
    ): String = try {
        stream.use { input ->
            val kept = ByteArrayOutputStream(MAX_CAPTURE_BYTES)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var totalBytes = 0L
            // 🔒 排水循环绝不能被消费端异常杀死：stdout/stderr 两个读取协程并发调用
            // 同一个 onOutput，若回调内部抛出未捕获异常（例如共享 StringBuilder 的数据
            // 竞争），本协程死亡后宿主管道无人读取，子进程写满内核管道缓冲（约 64KB）
            // 后 write() 永久阻塞，整棵构建进程树随之挂死、日志冻结在一半。
            // 因此回调异常在此兜底捕获：连续失败仅停用回调，排水必须继续。
            var callback = onOutput
            var consecutiveCallbackFailures = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) { capture?.finished(); break }
                capture?.add(buffer, read)
                totalBytes += read
                val remaining = MAX_CAPTURE_BYTES - kept.size()
                if (remaining > 0) kept.write(buffer, 0, minOf(read, remaining))
                if (callback != null && read > 0) {
                    val chunk = String(buffer, 0, read, Charsets.UTF_8)
                    try {
                        callback(chunk)
                        consecutiveCallbackFailures = 0
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (t: Throwable) {
                        consecutiveCallbackFailures++
                        if (consecutiveCallbackFailures >= MAX_CALLBACK_FAILURES) {
                            // 回调持续崩溃时放弃流式转发（CommandResult 仍保留完整输出），
                            // 但绝不能停止读管道——那会把正在运行的 Gradle/Flutter 卡死。
                            callback = null
                        }
                    }
                }
            }
            buildString {
                append(kept.toString(Charsets.UTF_8.name()))
                if (totalBytes > kept.size()) {
                    append("\n\n[进程输出已截断：共 ")
                    append(totalBytes)
                    append(" 字节，仅保留前 ")
                    append(kept.size())
                    append(" 字节]")
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        // Timeout teardown closes the process streams from this thread while a
        // reader is blocked in read(); Android surfaces that as
        // InterruptedIOException. Treat it as EOF: the timeout already produced
        // the authoritative CommandResult, and the reader failure must not
        // override it through structured-concurrency propagation.
        ""
    }

    private companion object {
        const val TIMEOUT_EXIT_CODE = 124
        const val PROCESS_TEARDOWN_TIMEOUT_MS = 1_000L

        /** 成功退出后等待输出管道 EOF 的排水宽限期：正常毫秒级，超时即放弃（孙进程持管道场景）。 */
        const val OUTPUT_DRAIN_TIMEOUT_MS = 5_000L
        const val MAX_CAPTURE_BYTES = 2 * 1024 * 1024
        const val READ_BUFFER_BYTES = 16 * 1024

        /** onOutput 连续抛异常达到该次数后停用流式回调，仅保留排水与完整输出捕获。 */
        const val MAX_CALLBACK_FAILURES = 8
    }
}
