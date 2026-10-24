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
import kotlinx.coroutines.runBlocking
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
    private val rootfsInstaller: UmlRootfsInstaller,
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
            // 超时时把 console 尾部一起报出来：只写「150s 没应答」等于什么都没说，
            // 用户与日志都看不出是 init 没跑、sshd 没起、还是 SSH 认证失败。
            val why = if (stopRequested) {
                "boot cancelled"
            } else {
                val tail = consoleTail(40).filter { it.isNotBlank() }
                val stage = tail.lastOrNull { it.contains("TIANYAN_UML_BOOT") || it.contains("STRYKER_BOOT") }
                val hint = when {
                    stage != null -> "guest init reached: $stage"
                    tail.isEmpty() -> "kernel produced no console output"
                    else -> "last: ${tail.last().trim().take(160)}"
                }
                "guest did not answer in ${timeoutMs / 1000}s ($hint)"
            }
            fail(why, onBooted)
        } catch (e: Exception) {
            logger.w("UML start failed", e)
            fail(e.message ?: e.javaClass.simpleName, onBooted)
        }
    }

    private fun start(memoryMb: Int, cpus: Int, seccomp: Boolean) {
        val share = layout.shareDir
        // 起进程前先清残留 guest。对齐 Stryker 的 reapStrayGuests-on-start：
        // 上一轮若被强杀（应用崩溃 / 用户划掉），内核进程会留在后台继续占着
        // 2222 端口和 ubd0 镜像文件，新一轮启动就绑不上端口 → umnet abort
        // （signal 6），或者镜像被占用读到脏数据。
        reapStrayGuests()
        // 清空上一轮 console：consoleTail 只取最后 N 行，若不清空，
        // 本轮启动失败时可能读到上一轮的 panic/Aborted，把诊断带偏。
        runCatching { layout.console.writeText("") }
        // 端口必须在起进程**之前**选定：umnet 绑定失败会直接 abort（signal 6），
        // 表现为「kernel killed by signal 6 (Aborted)」，看不出是端口冲突。
        hostSshPort = claimHostPort(UmlRuntimeLayout.HOST_SSH_PORT)
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
        // init 解析：**镜像自带的优先**，注入只作后备。
        //
        // 为什么不再强制注入：纯 Kotlin 的 ext4 写入器在我用 mkfs 造的小镜像上
        // 验证过，但在真实 Stryker 镜像（321MB、可能带 metadata_csum/flex_bg/
        // 目录索引）上写不进去 —— 强制注入会让安装直接失败，比不注入更糟。
        //
        // 镜像自带的 init 能跑就用它（Stryker 自己也是 init=/stryker-init），
        // 只是公钥要放到 $SHARE/.ssh/authorized_keys（已修）。
        // 一个都探测不到时才尝试注入天衍自己的那份。
        // 探测（读 ext4 根目录）在真实镜像上可能失败（大镜像 / flex_bg / 目录索引），
        // 所以探测不到时**不注入、也不放弃**，直接用 Stryker 的 /stryker-init ——
        // 那是 Stryker 自己在同一镜像上验证过的路径，最可能可用。
        // 注入只作为最后手段，且永不阻断启动。
        val imageInit = detectGuestInit()
        val initPath = imageInit ?: run {
            stageInit(share)
            val injected = runCatching {
                runBlocking(Dispatchers.IO) { rootfsInstaller.ensureGuestInit() }
            }.getOrDefault(false)
            if (injected) {
                logger.i("UML injected its own init (image had none detectable)")
                "/${UmlRootfsInstaller.GUEST_INIT_NAME}"
            } else {
                logger.i("UML falling back to /stryker-init (probe and injection both unavailable)")
                "/stryker-init"
            }
        }
        logger.i("UML guest init resolved to: $initPath")
        cmd += "init=$initPath"
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

    /**
     * 探测 rootfs 里实际存在的 init。
     *
     * 优先级：Stryker 的 stryker-init（会挂 hostfs + 起 sshd，功能最全）
     * → /sbin/init（systemd/传统 init）→ /init。
     * 都不存在时退回 stryker-init，让内核报的错与镜像实际内容对应得上。
     */
    private fun detectGuestInit(): String? {
        // 只列根目录下的候选：子路径（/sbin/init）需要两级目录解析，不在探测范围
        val candidates = listOf("stryker-init", "tianyan-uml-init", "init")
        val found = runCatching {
            Ext4InlineWriter.pickExistingRootEntry(layout.rootfs, candidates)
        }.getOrNull()
        if (found == null) {
            // 探测不到就把镜像根目录列出来记进日志：两个版本分别踩过
            // 「镜像没有 stryker-init」和「init 在但依赖脚本缺失」，
            // 靠猜会反复出错，列出真实内容最省事（日志面板可直接复制）。
            val listing = runCatching { Ext4InlineWriter.listRootEntries(layout.rootfs) }
                .getOrDefault(emptyList())
            logger.w(
                "no known guest init found; rootfs ${layout.rootfs.name} contains: " +
                    listing.take(40).joinToString(", ") +
                    (if (listing.size > 40) " …(+${listing.size - 40})" else ""),
            )
        }
        return found?.let { "/$it" }
    }

    /**
     * 挑一个可用回环端口。
     *
     * 对齐 Stryker 的 GuestPort.claim：首选端口被占（上一轮 guest 没退干净、
     * 或别的应用占了 2222）就向后找，而不是硬绑 —— 硬绑时 umnet 会 abort，
     * 用户只看到 signal 6，根本不知道是端口冲突。
     */
    private fun claimHostPort(preferred: Int): Int {
        if (isLoopbackPortFree(preferred)) return preferred
        for (port in (preferred + 1)..(preferred + PORT_SEARCH_SPAN).coerceAtMost(65535)) {
            if (isLoopbackPortFree(port)) {
                logger.w("UML host port $preferred is taken, using $port instead")
                return port
            }
        }
        logger.w("no free loopback port near $preferred; falling back to it anyway")
        return preferred
    }

    private fun isLoopbackPortFree(port: Int): Boolean = runCatching {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(java.net.InetSocketAddress(UmlRuntimeLayout.HOST_LOOPBACK, port), 1)
        }
        true
    }.getOrDefault(false)

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

    /**
     * 从 console 尾部提炼失败原因。
     *
     * 只取「最后一条匹配行」信息量太低（实测只报 `signal 6`，看不出是端口冲突
     * 还是镜像问题）。这里按严重度排序取最能说明问题的一条，并附上最后几行
     * 原始输出，让用户/日志能直接定位。
     */
    private fun consoleProblem(): String {
        val tail = consoleTail(200).filter { it.isNotBlank() }
        if (tail.isEmpty()) return "console is empty — the kernel produced no output at all"
        // 严重度：内核 panic > abort/信号 > 具体错误 > 泛泛的 failed
        val ordered = listOf(
            "Kernel panic",
            "Aborted",
            "killed by signal",
            "Exec format error",
            "Permission denied",
            "MADV_REMOVE",
            "address already in use",
            "Address already in use",
            "cannot",
            "could not",
            "couldn't",
            "failed",
        )
        for (needle in ordered) {
            tail.lastOrNull { it.contains(needle, ignoreCase = true) }?.let { hit ->
                val context = tail.takeLast(6).joinToString(" | ") { it.trim().take(160) }
                return "$hit  ‖ last: $context"
            }
        }
        return "no recognizable error; last lines: " + tail.takeLast(4).joinToString(" | ") { it.trim().take(160) }
    }

    companion object {
        const val DEFAULT_RAM_MB = 2048
        /** 端口探测跨度：与 Stryker GuestPort.SEARCH_SPAN 同量级。 */
        private const val PORT_SEARCH_SPAN = 32
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
