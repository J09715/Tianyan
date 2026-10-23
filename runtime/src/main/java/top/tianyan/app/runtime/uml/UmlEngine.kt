package top.tianyan.app.runtime.uml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.runtime.shell.LinuxSession
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * UML（User Mode Linux）引擎：把一颗 guest 内核作为普通用户态进程直接拉起，
 * 无 CPU 仿真（区别于 QEMU-TCG），guest 内代码在本机 Cortex 上原生执行，因此流畅。
 *
 * 移植自 Stryker UmlEngine（GPL v3），裁剪为：
 * - 内核 + stub + umnet(passt 网络) 三件套，从 nativeLibraryDir 执行；
 * - passt 做 hostfwd，把 guest:22 映射到 127.0.0.1:HOST_SSH_PORT；
 * - 通过 [UmlGuestSsh] 进 guest；
 * - hostfs 共享目录注入公钥 + 取回 host key；
 * - 引导探测（内核 banner / 失败诊断）与进程回收。
 *
 * 不搬 Stryker 的 USB-gadget / Wi-Fi monitor —— 天衍无 root、无 USB configFS，纯负担。
 */
@Singleton
class UmlEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val layout: UmlRuntimeLayout,
    private val guestSsh: UmlGuestSsh,
    private val logger: AppLogger,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tianyan-uml").apply { isDaemon = true }
    }

    @Volatile
    private var process: Process? = null
    @Volatile
    private var consolePump: Thread? = null
    @Volatile
    private var hostSshPort: Int = UmlRuntimeLayout.HOST_SSH_PORT
    @Volatile
    private var lastError: String = ""
    @Volatile
    private var lastGuestOk: Long = 0L
    @Volatile
    private var stopRequested: Boolean = false

    private val portMirror = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tianyan-uml-port-mirror").apply { isDaemon = true }
    }

    private val mirrored = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val mirrorFailedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    enum class State { STOPPED, BOOTING, READY }

    fun type(): EngineType = EngineType.UML

    fun displayName(): String = "User Mode Linux"

    fun isInstalled(): Boolean = layout.isInstalled()

    fun missing(): List<String> = layout.missing()

    fun sshPort(): Int = hostSshPort

    fun lastError(): String = lastError

    fun isRunning(): Boolean {
        val p = process
        return p != null && p.isAlive
    }

    fun isReady(): Boolean = isRunning() && guestSsh.ping(READY_PING_TIMEOUT_MS)

    fun status(): State {
        if (!isRunning()) return State.STOPPED
        val fresh = System.currentTimeMillis() - lastGuestOk < GUEST_FRESH_MS
        return if (fresh && lastGuestOk >= guestSsh.lastLossAt()) State.READY else State.BOOTING
    }

    fun statusBlocking(): State {
        if (!isRunning()) return State.STOPPED
        if (guestSsh.ping(READY_PING_TIMEOUT_MS)) {
            lastGuestOk = System.currentTimeMillis()
            return State.READY
        }
        return State.BOOTING
    }

    fun consoleTail(lines: Int): List<String> =
        runCatching {
            layout.console.readText().lines()
        }.getOrElse { emptyList() }.takeLast(lines)

    /** 非阻塞启动：丢到后台 executor，引导结果通过 [onBooted]/[onFailed] 回调。 */
    fun startAsync(onBooted: (() -> Unit)? = null, onBootLine: ((String) -> Unit)? = null) {
        stopRequested = false
        executor.execute {
            if (startBlocking(onBootLine = onBootLine, onBooted = onBooted)) {
                logger.i("UML guest up at ${UmlRuntimeLayout.HOST_LOOPBACK}:$hostSshPort")
            } else {
                logger.w("UML guest boot failed: $lastError")
            }
        }
    }

    /** 同步启动并阻塞直到 guest SSH 应答或超时。 */
    fun startBlocking(onBootLine: ((String) -> Unit)? = null, onBooted: (() -> Unit)? = null): Boolean =
        startBlocking(BOOT_TIMEOUT_MS, onBootLine, onBooted)

    /** 同步启动，带自定义超时（命令路径用短超时，避免一次命令等 150s）。 */
    fun startBlocking(
        timeoutMs: Long,
        onBootLine: ((String) -> Unit)? = null,
        onBooted: (() -> Unit)? = null,
    ): Boolean {
        stopRequested = false
        return try {
            if (isRunning() && guestSsh.ping(BOOT_PING_TIMEOUT_MS)) {
                startPortMirror()
                onBooted?.invoke()
                return true
            }
            val gaps = layout.missing()
            if (gaps.isNotEmpty()) return fail(gaps.joinToString(", "), onBooted)

            layout.ensureDirectories()
            guestSsh.configure(context, layout.shareDir, hostSshPort)
            runCatching { guestSsh.publishPublicKey() }
                .onFailure { logger.w("UML publishPublicKey failed", it) }

            val ramMb = context.getSharedPreferences("tianyan-uml", Context.MODE_PRIVATE)
                .getInt("ram_mb", DEFAULT_RAM_MB)
            val cpus = context.getSharedPreferences("tianyan-uml", Context.MODE_PRIVATE)
                .getInt("cpus", DEFAULT_CPUS)

            start(ramMb, cpus, seccomp = true)
            onBootLine?.invoke("UML kernel started (${ramMb}MB / ${cpus}vCPU)")

            val deadline = System.currentTimeMillis() + timeoutMs
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                if (!isRunning()) {
                    val code = process?.let { runCatching { it.exitValue() }.getOrNull() }
                    return fail("guest exited (${code?.toString() ?: "?"}): ${consoleProblem()}", onBooted)
                }
                if (guestSsh.ping(BOOT_PING_TIMEOUT_MS)) {
                    lastError = ""
                    lastGuestOk = System.currentTimeMillis()
                    startPortMirror()
                    onBooted?.invoke()
                    return true
                }
                Thread.sleep(1000)
            }
            fail(if (stopRequested) "boot cancelled" else "guest did not answer in ${timeoutMs / 1000}s", onBooted)
        } catch (e: Exception) {
            logger.w("UML start failed", e)
            fail(e.message ?: e.javaClass.simpleName, onBooted)
        }
    }

    private fun start(memoryMb: Int, cpus: Int, seccomp: Boolean) {
        val share = layout.shareDir
        val cmd = buildCommand(share, memoryMb, cpus, seccomp)
        logger.i("UML starting: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
        pb.directory(layout.baseDir)
        pb.redirectErrorStream(true)
        val tmp = fallbackTempDir()
        if (tmp != null) pb.environment()["TMPDIR"] = tmp
        val p = pb.start()
        process = p
        startConsolePump(p)
    }

    private fun buildCommand(share: File, memoryMb: Int, cpus: Int, seccomp: Boolean): List<String> {
        val cmd = mutableListOf<String>()
        // 网络栈：umnet 用 passt 做 hostfwd，-- 之后是 UML 内核参数
        cmd += layout.umnet.absolutePath
        cmd += "--passt"
        cmd += layout.passt.absolutePath
        cmd += "--fwd"
        cmd += "${UmlRuntimeLayout.HOST_LOOPBACK}/$hostSshPort:${UmlRuntimeLayout.GUEST_SSH_PORT}"
        cmd += "--"
        // 内核：mem/panic/con/stub/seccomp/ncpus/ubd0/root
        cmd += layout.kernel.absolutePath
        cmd += "mem=${maxOf(memoryMb, MIN_RAM_MB)}M"
        cmd += "panic=-1"
        cmd += "con=null"
        cmd += "con0=fd:0,fd:1"
        cmd += "stub_exe=${layout.stub.absolutePath}"
        cmd += "seccomp=" + if (seccomp) "auto" else "off"
        if (cpus > 0) cmd += "ncpus=$cpus"
        cmd += "ubd0=${layout.rootfs.absolutePath}"
        cmd += "root=/dev/ubda"
        cmd += "rw"
        // 用天衍自己的 guest init：挂 proc/dev、配 passt 网络、起 sshd、支持单命令模式。
        // 内核参数 init= 指向 share 目录里的脚本（guest 把 share 挂到 /host）。
        stageInit(share)
        cmd += "init=/host/tianyan-uml-init"
        // hostfs 共享目录：guest 里挂在 /host，应用把公钥/init 丢进去、把 host key 取出来
        cmd += "stryker.share=${share.absolutePath}"
        // 用户工作区：宿主 filesDir/linux-runtime/workspace 挂进 guest 的 /workspace
        cmd += "stryker.workspace=${layout.workspaceDir.absolutePath}"
        cmd += "firmware_class.path=/host/firmware"
        return cmd
    }

    /** 把 APK 资源里的 init 脚本落到共享目录（guest 会把它挂到 /host）。 */
    private fun stageInit(share: File) {
        val staged = File(share, "tianyan-uml-init")
        runCatching {
            context.assets.open("tianyan-uml/tianyan-uml-init").use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            staged.setExecutable(true, false)
        }.onFailure {
            logger.w("failed to stage UML init into share dir: ${it.message}")
        }
    }

    /** 给 guest 内存找一个可写 tmpfs；找不到就回退 filesDir（UML 会告警但不致命）。 */
    private fun fallbackTempDir(): String? {
        for (candidate in listOf("/dev/shm", "/tmp")) {
            val d = File(candidate)
            if (d.isDirectory && d.canWrite()) {
                File(d, "tianyan-uml").mkdirs()
                return candidate
            }
        }
        val base = layout.baseDir
        base.mkdirs()
        return base.absolutePath
    }

    // ==================== 执行入口：UML 作为沙箱引擎 ====================

    /**
     * 一次性命令：在 UML guest 里执行，带退出码哨兵。
     *
     * 这是 UML 成为「执行引擎」的核心——上层（工具、MCP、脚本）调 execute() 时，
     * 只要 UML 生效就走这里，命令直接跑在 guest 的完整 root Linux 里。
     */
    suspend fun exec(command: top.tianyan.app.runtime.shell.ShellCommand): top.tianyan.app.runtime.shell.CommandResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val sentinel = EXIT_SENTINEL + System.nanoTime()
            val wrapped = buildString {
                append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH; ")
                append("export HOME=").append(GUEST_HOME).append(" LANG=C.UTF-8; ")
                append("cd '").append(command.workingDirectory).append("' 2>/dev/null; ")
                append(command.commandLine)
                append("\nprintf '\\n").append(sentinel).append("%s\\n' \"\$?\"\n")
            }

            val channel = try {
                guestSsh.openExecChannel(wrapped)
            } catch (e: Exception) {
                logger.w("UML exec: no guest session", e)
                return@withContext top.tianyan.app.runtime.shell.CommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "UML guest unavailable: ${e.message}",
                    durationMs = System.currentTimeMillis() - started,
                )
            }

            val stdout = StringBuilder()
            var exit = -1
            try {
                channel.connect()
                val reader = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
                withTimeout(command.timeoutMs) {
                    runInterruptible {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith(sentinel)) {
                                exit = line.substring(sentinel.length).trim().toIntOrNull() ?: -1
                                break
                            }
                            stdout.append(line).append('\n')
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                logger.w("UML exec timed out after ${command.timeoutMs}ms")
            } catch (e: Exception) {
                logger.w("UML exec failed: ${e.message}")
            } finally {
                runCatching { channel.disconnect() }
            }

            top.tianyan.app.runtime.shell.CommandResult(
                exitCode = exit,
                stdout = stdout.toString(),
                stderr = "",
                durationMs = System.currentTimeMillis() - started,
            )
        }

    /** 交互式终端会话：guest 里的 shell channel（带 PTY，可 resize）。 */
    suspend fun openSession(config: top.tianyan.app.runtime.shell.SessionConfig): LinuxSession =
        withContext(Dispatchers.IO) {
            val channel = guestSsh.openShellChannel(config.columns, config.rows)
            channel.connect()
            UmlSandboxSession(
                channel = channel,
                input = channel.inputStream,
                out = channel.outputStream,
                label = "shell",
            )
        }

    /** 后台进程 / 协议会话：guest 里的 exec channel（无 PTY，按行分帧）。 */
    suspend fun openExecSession(command: top.tianyan.app.runtime.shell.ShellCommand): LinuxSession =
        withContext(Dispatchers.IO) {
            val wrapped = buildString {
                append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH; ")
                append("export HOME=").append(GUEST_HOME).append(" LANG=C.UTF-8; ")
                append("cd '").append(command.workingDirectory).append("' 2>/dev/null; ")
                append(command.commandLine)
                append("\n")
            }
            val channel = guestSsh.openExecChannel(wrapped)
            channel.connect()
            UmlSandboxSession(
                channel = channel,
                input = channel.inputStream,
                out = channel.outputStream,
                label = "exec",
            )
        }

    /** guest 是否已就绪（可直接执行命令）。 */
    fun isGuestReady(): Boolean = isRunning() && guestSsh.ping(READY_PING_TIMEOUT_MS)

    /**
     * 校验 guest 里确实是 root（uid=0）。
     * 「没有完整 root 权限」是最容易含糊过去的故障——这里把它变成一条明确结论：
     * 返回 uid/uname/gid，非 0 时调用方能直接告诉用户 guest 没给 root，而不是静默降级。
     */
    fun guestIdentity(): String =
        runCatching { guestSsh.run("id -u; id -un; id -g").joinToString(" ") }
            .getOrElse { "unreachable" }
            .trim()

    fun isGuestRoot(): Boolean = guestIdentity().startsWith("0 ")

    private fun startConsolePump(p: Process) {
        val thread = Thread({
            BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    layout.console.appendText("$line\n")
                    if (line.startsWith("STRYKER_BOOT") || line.startsWith("STRYKER_INIT")) {
                        logger.i("UML console: $line")
                    }
                }
            }
        }, "tianyan-uml-console")
        thread.isDaemon = true
        thread.start()
        consolePump = thread
    }

    private fun startPortMirror() {
        val executor = portMirror
        if (executor.isShutdown) return
        executor.execute {
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                runCatching { syncPorts() }
                Thread.sleep(PORT_MIRROR_PERIOD_MS)
            }
        }
    }

    private fun syncPorts() {
        if (!isReady()) return
        val live = mutableSetOf<Int>()
        guestSsh.run("ss -ltnH 2>/dev/null | awk '{print \$4}' | sed 's/.*://' | sort -un").forEach { line ->
            val trimmed = line.trim()
            val port = trimmed.toIntOrNull() ?: return@forEach
            if (port in 1025..65535 && port != UmlRuntimeLayout.GUEST_SSH_PORT && port != hostSshPort) {
                live.add(port)
            }
        }
        for (port in live) {
            if (port in mirrored) continue
            val failedAt = mirrorFailedAt[port]
            if (failedAt != null && System.currentTimeMillis() - failedAt < PORT_MIRROR_RETRY_MS) continue
            if (guestSsh.forwardLocalPort(port, port)) {
                mirrored.add(port)
                mirrorFailedAt.remove(port)
                logger.i("UML guest port $port mirrored to 127.0.0.1:$port")
            } else {
                mirrorFailedAt[port] = System.currentTimeMillis()
            }
        }
        val gone = (mirrored + mirrorFailedAt.keys).filter { it !in live }
        for (port in gone) {
            guestSsh.unforwardLocalPort(port)
            mirrored.remove(port)
            mirrorFailedAt.remove(port)
        }
    }

    fun stop() {
        stopRequested = true
        runCatching { guestSsh.run("nohup poweroff >/dev/null 2>&1 &") }
        val p = process
        for (i in 0 until 30) {
            if (p == null || !p.isAlive) break
            Thread.sleep(200)
        }
        p?.let {
            if (it.isAlive) it.destroy()
            for (i in 0 until 10) {
                if (!it.isAlive) break
                Thread.sleep(200)
            }
            if (it.isAlive) it.destroyForcibly()
        }
        reapStrayGuests()
        guestSsh.disconnect()
        process = null
        consolePump?.interrupt()
        consolePump = null
        portMirror.shutdownNow()
    }

    fun stopAndWait(timeoutMs: Long): Boolean {
        stop()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        return !isRunning()
    }

    private fun reapStrayGuests() {
        val marker = layout.nativeDir.absolutePath
        val pids = File("/proc").list()?.filter { it.toIntOrNull() != null }?.mapNotNull {
            val pid = it.toIntOrNull() ?: return@mapNotNull null
            val cmdline = runCatching {
                File("/proc/$pid/cmdline").readBytes()
                    .map { it.toInt() and 0xff }
                    .filter { it != 0 }
                    .map { it.toChar() }
                    .joinToString("")
            }.getOrNull() ?: return@mapNotNull null
            if (pid == android.os.Process.myPid()) return@mapNotNull null
            if (cmdline.contains(marker) && (cmdline.contains("libuml") || cmdline.contains("libumnet") || cmdline.contains("libpasst"))) {
                pid
            } else null
        }
        pids?.forEach { android.os.Process.killProcess(it) }
    }

    private fun fail(reason: String, onBooted: (() -> Unit)?): Boolean {
        lastError = reason
        logger.w("UML boot failed: $reason")
        return false
    }

    private fun consoleProblem(): String {
        val tail = consoleTail(200)
        val problems = tail.filter { line ->
            line.contains("Kernel panic") || line.contains("Aborted") ||
                line.contains("cannot") || line.contains("could not") || line.contains("failed") ||
                line.contains("Permission denied") || line.contains("Exec format error") ||
                line.contains("MADV_REMOVE")
        }
        return if (problems.isEmpty()) "see ${layout.console.name}" else problems.last()
    }

    companion object {
        const val DEFAULT_RAM_MB = 2048
        const val DEFAULT_CPUS = 4
        const val MIN_RAM_MB = 512
        /** 一次性命令的退出码哨兵前缀（拼上纳秒时间戳，避免与命令自身输出撞名）。 */
        private const val EXIT_SENTINEL = "__TIANYAN_UML_EXIT__"
        /** guest 里会话的 HOME：挂进来的用户工作区（不是 guest 镜像自带的 /root）。 */
        private const val GUEST_HOME = "/workspace"
        private const val BOOT_TIMEOUT_MS = 150_000L
        private const val BOOT_PING_TIMEOUT_MS = 30_000
        private const val READY_PING_TIMEOUT_MS = 3_000
        private const val GUEST_FRESH_MS = 15_000L
        private const val PORT_MIRROR_PERIOD_MS = 10_000L
        private const val PORT_MIRROR_RETRY_MS = 60_000L
    }
}

/** 引擎抽象：UML 为默认执行引擎（完整 root Linux 系统，自带内核 + root）；PRoot 为兼容/兜底引擎。 */
enum class EngineType { PROOT, UML, QEMU_ROOTLESS }
