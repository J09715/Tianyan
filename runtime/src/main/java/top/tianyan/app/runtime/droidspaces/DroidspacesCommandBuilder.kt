package top.tianyan.app.runtime.droidspaces

import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.shell.ShellQuote
import top.tianyan.app.core.model.StorageMountBinding
import top.tianyan.app.runtime.EnvironmentResolver
import top.tianyan.app.runtime.backend.GuestCommandBuilder
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand
import java.io.File

/**
 * Droidspaces 容器后端：把客态命令交给 `droidspaces run` 在真实容器命名空间内执行。
 *
 * 与 PRoot 后端的本质差异：
 * - **需要 Root**（`su -c` 进入 UID 0），因此本构建器只在 `ExecutionMode.ROOT` 下装配；
 * - 客态是**真内核命名空间**，无 syscall 拦截开销、原生 ext4 语义（硬链接/lchown 正常）；
 * - 容器**生命周期独立于单次命令**：挂载 `-B` 只在 `start` 时生效，运行期无法追加。
 *
 * 因此本类不写 Python/Kotlin 侧的容器管理状态机，而是把「确保容器在跑」下沉到
 * 宿主编排脚本（见 [lifecyclePrelude]），保证 builder 仍是纯函数：同样入参必得同样 argv。
 */
class DroidspacesCommandBuilder private constructor(
    private val environmentResolver: EnvironmentResolver,
    private val logWarning: (String) -> Unit,
    private val config: DroidspacesConfig,
) : GuestCommandBuilder {

    constructor(
        environmentResolver: EnvironmentResolver,
        logger: AppLogger,
    ) : this(environmentResolver, { message -> logger.w(message) }, DroidspacesConfig())

    /** JVM 单测无需 Android 日志与真实设备，仅校验纯参数构造。 */
    internal constructor(
        environmentResolver: EnvironmentResolver,
    ) : this(environmentResolver, {}, DroidspacesConfig())

    override fun build(
        prootBinary: File,
        rootfsDir: File,
        workspaceDir: File,
        homeDir: File,
        optDir: File,
        tmpDir: File,
        attachmentsDir: File,
        command: ShellCommand,
        mounts: List<StorageMountBinding>,
        emulatorBinary: File?,
        hostCredential: Map<String, String>,
    ): List<String> {
        // QEMU 兼容模式依赖 PRoot 的 `-q` 用户态仿真；Droidspaces 复用宿主内核，
        // 无法提供 x86_64 指令翻译。静默放行会得到「命令看似成功、实际架构不符」的假成功。
        require(emulatorBinary == null) {
            "Droidspaces 后端不支持 x86_64 QEMU 兼容模式；请在设置中切回 PRoot 后端，或改用 native ARM64 发行版"
        }
        val environment = environmentResolver.merge(provider = command.environment) + hostCredential
        val inner = guestCommand(
            commandLine = command.commandLine,
            workingDirectory = command.workingDirectory,
            environment = environment,
            pty = command.forcePty,
        )
        return hostArgv(
            workspaceDir = workspaceDir,
            homeDir = homeDir,
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            mounts = mounts,
            inner = inner,
        )
    }

    override fun buildInteractive(
        prootBinary: File,
        rootfsDir: File,
        workspaceDir: File,
        homeDir: File,
        optDir: File,
        tmpDir: File,
        attachmentsDir: File,
        config: SessionConfig,
        ptyMarker: String?,
        nativePty: Boolean,
        mounts: List<StorageMountBinding>,
        emulatorBinary: File?,
        hostCredential: Map<String, String>,
    ): List<String> {
        require(emulatorBinary == null) {
            "Droidspaces 后端不支持 x86_64 QEMU 兼容模式；请在设置中切回 PRoot 后端，或改用 native ARM64 发行版"
        }
        if (config.usePty && !nativePty) {
            // 协议会话（MCP STDIO）必须保持纯管道，否则 banner/提示符会污染输出流。
            logWarning("Droidspaces: 交互会话缺少原生 PTY，已退化为 run + script 伪终端")
        }
        val columns = config.columns.coerceIn(20, 400)
        val rows = config.rows.coerceIn(5, 200)
        val environment = environmentResolver.merge(
            provider = config.environment,
            interactive = true,
        ) + hostCredential
        val bannerPrelude = if (config.showBanner) "cat $GUEST_MOTD 2>/dev/null; " else ""
        val markerPrelude = ptyMarker?.also {
            require(PTY_MARKER.matches(it)) { "PTY marker path is invalid" }
        }?.let { "tty > $it; " }.orEmpty()
        // `droidspaces run` 不分配 PTY（实测 NOTTY），几何信息只能口头下发；
        // 需要真 TTY 的交互场景应走 `enter`，此处保持与 PRoot 后端的 script 退化一致。
        val geometry = if (config.usePty) "stty cols $columns rows $rows 2>/dev/null; " else ""
        val inner = guestCommand(
            commandLine = bannerPrelude + markerPrelude + geometry + config.commandLine,
            workingDirectory = config.workingDirectory,
            environment = environment,
            pty = false,
        )
        return hostArgv(
            workspaceDir = workspaceDir,
            homeDir = homeDir,
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            mounts = mounts,
            inner = inner,
        )
    }

    /** 客态侧命令行：用 `env` 前缀注入变量，避免为每次执行落盘 `-E` 环境文件。 */
    private fun guestCommand(
        commandLine: String,
        workingDirectory: String,
        environment: Map<String, String>,
        pty: Boolean,
    ): String {
        val exports = environment.entries
            .sortedBy { it.key }
            .joinToString(" ") { (key, value) ->
                require(ENVIRONMENT_KEY.matches(key)) { "Invalid environment variable name: $key" }
                "$key=${ShellQuote.of(value)}"
            }
        // 严禁在此加 `exec` 前缀：调用方传入的是任意 shell 片段（`a; b`、`cd x && …`、
        // `if …` 等），`exec` 只接受一条简单命令，加了会让复合命令的后续语句不执行、
        // 内建命令直接失败。工作目录由 `cd` 设置（`run_in_rootfs` 在 pivot 后 chdir("/")）。
        //
        // `cd` 失败必须给出可操作诊断，并写明目标目录。常见原因有两种：目录本身不存在，
        // 或该路径尚未经 `-B` 绑定挂载进容器（`-B` 只在 `start` 时生效，见 [lifecyclePrelude]）。
        // 放任 `cd` 静默失败时，用户只会看到 `can't cd to …`。诊断只写 stderr，退出码 125。
        // `printf` 是 shell 内建，不依赖宿主上缺失的外部文本工具。
        //
        // 天衍并不是「从不修改用户容器」：上游 `start` 会把 CLI `-B` 按 src/dst 去重后写回
        // Containers/<name>/container.config，之后移除或停用的存储挂载仍可能留在其中。
        val quotedDir = ShellQuote.of(workingDirectory)
        val body = "cd -- $quotedDir || { " +
            "printf '%s%s%s\\n' '[tianyan] 无法进入工作目录 ' $quotedDir " +
            "'：该目录不存在，或未通过 -B 绑定挂载进容器。请确认路径；容器正在运行时需停止后重新 start 才能补上挂载。" +
            "上游 start 会把本次 -B 按 src/dst 去重后写回容器配置，之后移除或停用的存储挂载仍可能留在其中。' " +
            ">&2; exit 125; }; " +
            commandLine
        val wrapped = if (pty) wrapInPty(body) else body
        // 变量为空时不加 `env` 前缀：避免 `env  sh -lc` 这类双空格与多余的 exec 层。
        return if (exports.isBlank()) {
            "sh -lc ${ShellQuote.of(wrapped)}"
        } else {
            "env $exports sh -lc ${ShellQuote.of(wrapped)}"
        }
    }

    /**
     * 宿主侧 argv：`su -c '<宿主编排脚本>'`。
     *
     * 脚本先确保容器处于运行态（`run` 要求容器已运行，见 Droidspaces `container.c`），
     * 再 `exec` 进入目标命令——`exec` 让 su 进程被直接替换，避免多一层 shell 残留
     * 导致退出码与信号转发失真。
     */
    private fun hostArgv(
        workspaceDir: File,
        homeDir: File,
        optDir: File,
        tmpDir: File,
        attachmentsDir: File,
        mounts: List<StorageMountBinding>,
        inner: String,
    ): List<String> {
        val container = config.containerName
        val script = lifecyclePrelude(
            container = container,
            binds = bindArguments(workspaceDir, homeDir, optDir, tmpDir, attachmentsDir, mounts),
        ) +
            "exec " + ShellQuote.of(config.cliPath) +
            " --name=" + ShellQuote.of(container) +
            // 不要加 `-u root`：droidspaces 的 `-u` 只在 main 的 discovery pass 里被解析，
            // 而该 pass 遇到 `run` 就 break（main.c:940-948），写在 `run` 之后既不会被解析，
            // 还会污染 `run_argv`（run_argv = argv + optind + 1），使容器命令变成以 `-u` 开头。
            // as_user 为空时 run_in_rootfs 直接 execvp（container.c:1650-1657），
            // 即在外层 `su -c` 已是 UID 0 的前提下以 root 在容器内执行——等价于 PRoot 的
            // `--change-id=0:0`；反之若传 `-u root`，droidspaces 会改走 `su - root -c`，
            // 要求客态存在 /bin/su 并重置登录环境，反而更脆。
            " run -- " + inner
        return listOf("su", "-c", script)
    }

    /**
     * 客态目录视图必须与 PRoot 后端**逐项对齐**，否则插件、工具链、MCP 能力全部失效：
     *
     * | PRoot `-b` | Droidspaces `-B` |
     * |---|---|
     * | `<base>/workspace` → `/workspace` | 同名 |
     * | `<base>/distros/<id>/opt/tianyan` → `/opt/tianyan` | 同名 |
     * | `<base>/tmp` → `/tmp` | 同名 |
     * | `<base>/attachments` → `/attachments` | 同名 |
     *
     * 宿主的 `/dev` `/proc` `/sys` 由容器自身提供，无需绑定。
     * 用户存储挂载沿用 [StorageMountBinding.validationError] 的统一校验，非法项直接跳过，
     * 与 PRoot 侧防挂载逃逸的最后防线保持一致。
     */
    private fun bindArguments(
        workspaceDir: File,
        homeDir: File,
        optDir: File,
        tmpDir: File,
        attachmentsDir: File,
        mounts: List<StorageMountBinding>,
    ): List<String> {
        // PRoot 后端绑定了 `${homeDir}:/root`，此处必须逐项对齐：否则客态的 HOME、
        // 凭据、配置与 shell 历史都和 PRoot 视图隔开，插件与工具链会看到两套环境。
        homeDir.mkdirs()
        // 与 PRoot 对齐：PRoot 在 build / buildInteractive 两条路径上都先 mkdirs 再绑定，
        // 这里由共享的 bindArguments 统一保证。
        attachmentsDir.mkdirs()
        val binds = mutableListOf(
            "${workspaceDir.absolutePath}:/workspace",
            "${homeDir.absolutePath}:/root",
            "${optDir.absolutePath}:/opt/tianyan",
            "${tmpDir.absolutePath}:/tmp",
            "${attachmentsDir.absolutePath}:/attachments",
        )
        mounts.filter { it.enabled }.forEach { binding ->
            val error = StorageMountBinding.validationError(binding)
            if (error != null) {
                logWarning("存储挂载绑定不合法，已跳过：${binding.hostPath}（$error）")
                return@forEach
            }
            val guest = StorageMountBinding.normalizeGuestPath(binding.guestPath) ?: return@forEach
            val host = File(binding.hostPath)
            if (!host.isDirectory || !host.canRead()) {
                logWarning("宿主挂载目录不可访问，已跳过：${binding.hostPath}")
                return@forEach
            }
            binds += "${host.absolutePath}:$guest"
        }
        return binds
    }

    /**
     * 容器存活兜底 + 挂载下发。
     *
     * 关键约束：Droidspaces 的 `-B` 只在 `start` 时读取，运行期无法追加。因此：
     * - 容器未运行 → `start` 带上本次挂载清单；
     * - 容器已运行 → 不重启（会打断其它会话），本次挂载差异不生效。
     *
     * 存活与否看 `droidspaces --name="$NAME" pid` 的退出码，不做文本解析：
     * 容器在跑时该命令 exit 0，未运行时 exit 1。不再读取 `show` 的表格输出，
     * 也不按词边界匹配容器名。
     *
     * 上游 `start` 会把 CLI `-B` 按 src/dst 去重后写回 `Containers/<name>/container.config`，
     * 之后移除或停用的存储挂载仍可能留在该文件里（见函数体内 TODO）。
     */
    private fun lifecyclePrelude(
        container: String,
        binds: List<String>,
    ): String {
        val cli = ShellQuote.of(config.cliPath)
        val bindFlags = binds.joinToString(" ") { "-B " + ShellQuote.of(it) }
        return buildString {
            append("BIN=").append(cli).append("; ")
            append("NAME=").append(ShellQuote.of(container)).append("; ")
            // CLI 可用性前置检查：同时覆盖绝对路径与 PATH 中的命令名两种装配方式。
            // 缺失时给出可诊断的报错，而不是让后续 run 报一个看不出原因的失败。
            append("if [ ! -x \"\$BIN\" ] && ! command -v \"\$BIN\" >/dev/null 2>&1; then ")
            append("echo \"[tianyan] 未找到可执行的 Droidspaces CLI：\$BIN。")
            append("请先安装 Droidspaces，或在设置中切回 PRoot 后端。\" >&2; exit 127; fi; ")
            // 运行态判定用 `pid` 子命令的**退出码**，不做任何文本解析：
            // 运行中 → 打印 PID 并 exit 0；未运行 → 打印 NONE 并 exit 1（main.c:1208-1218）。
            // 这替换了此前 `show | grep -Eq` 的实现——宿主侧执行环境是 Android 的
            // /system/bin/sh，实测**没有可用的 grep**（报 "grep: inaccessible or not found"），
            // 依赖外部文本工具会让整条链路直接崩掉。
            append("if \$BIN --name=\"\$NAME\" pid >/dev/null 2>&1; then :; ")
            // 容器未运行才需要 start。**刻意不传 `-r`**：
            // 用户自建的容器（如 `tianyan`）在 Containers/<name>/container.config 里已有自己的
            // rootfs 目标（可能是 ext4 镜像），用 PRoot 的 rootfs 目录去覆盖它既会触发
            // 「Both rootfs directory and image specified」冲突，也会破坏用户已有容器。
            // 代价是：若该容器从未创建过，start 会因缺少 rootfs 而失败——此时报错由
            // droidspaces 原样透出，用户可据配置自行创建，而不是被我们猜一个 rootfs 塞进去。
            //
            // TODO: 上游 `start` 会把本次 CLI `-B` 按 src/dst 去重后追加写回
            // Containers/<name>/container.config。之后在天衍里移除或停用的存储挂载仍可能
            // 留在该文件中。后续应与当前挂载对齐；此处不改变 start 的参数与调用时机。
            append("elif ! \$BIN start --name=\"\$NAME\"")
            if (bindFlags.isNotBlank()) append(' ').append(bindFlags)
            // start 会打印 banner 与进度，属于人类可读输出，必须留在 stderr/丢弃，
            // 否则会污染非交互命令的 stdout。失败原因走 ds_error（stderr）原样透出。
            append(" >/dev/null; then ")
            append("echo \"[tianyan] Droidspaces 容器 \$NAME 启动失败（上方为 droidspaces 原始报错）。")
            append("请确认已授予 root、rootfs 可用且内核支持容器。\" >&2; exit 125; fi; ")
        }
    }

    private fun wrapInPty(commandLine: String): String =
        "if command -v script >/dev/null 2>&1; then " +
            "exec script -qfec " + ShellQuote.of(commandLine) + " /dev/null; " +
            "else $commandLine; fi"

    companion object {
        const val GUEST_MOTD = "/opt/tianyan/motd"
        val PTY_MARKER = Regex("/opt/tianyan/\\.pty-[A-Za-z0-9-]{8,64}")
        val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * Droidspaces 后端装配参数。
 *
 * [cliPath] 默认指向官方安装位置；[containerName] 是**用户已创建**的 Droidspaces 容器名。
 *
 * 重要：天衍**不自行创建容器**，也不传 `-r` 覆盖用户已有的 rootfs。容器由用户通过
 * Droidspaces 创建并持有自己的 rootfs 目标（可能是 ext4 镜像）。用派生名
 * （如 `tianyan-<distro>`）会指向一个不存在的容器，或把 PRoot 的 rootfs 目录
 * 硬塞给一个已有镜像型容器，两者都会失败。
 *
 * 容器未运行时调用的上游 `start` 会把本次 CLI `-B` 按 src/dst 去重后写回
 * `Containers/<name>/container.config`，因此后来移除或停用的存储挂载仍可能留在该文件里。
 *
 * 关于环境变量：Droidspaces 的 `-E` 环境文件必须位于其 workspace 目录下
 * （`config.c` 有显式校验），为避免每次执行落盘临时文件，本实现改用 `env` 前缀注入。
 */
data class DroidspacesConfig(
    val cliPath: String = "/data/local/Droidspaces/bin/droidspaces",
    val containerName: String = "tianyan",
)
