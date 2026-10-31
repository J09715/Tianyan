package top.tianyan.app.runtime.droidspaces

import top.tianyan.app.core.common.shell.ShellQuote
import top.tianyan.app.core.model.ExecutionMode
import top.tianyan.app.core.model.StorageMountBinding
import top.tianyan.app.runtime.EnvironmentResolver
import top.tianyan.app.runtime.backend.ModeBackendSelector
import top.tianyan.app.runtime.proot.ProotCommandBuilder
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Droidspaces 后端参数构造的纯函数测试。
 *
 * 只验证 argv 形态（可离线复算），不验证真机行为——容器是否真能跑由 P0/P5 门禁与真机验证覆盖。
 */
class DroidspacesCommandBuilderTest {

    private val rootfsDir = File("/data/data/app/files/linux-runtime/distros/ubuntu/rootfs")
    private val workspaceDir = File("/data/data/app/files/linux-runtime/workspace")
    private val optDir = File("/data/data/app/files/linux-runtime/distros/ubuntu/opt/tianyan")
    private val tmpDir = File("/data/data/app/files/linux-runtime/tmp")
    private val attachmentsDir = File("/data/data/app/files/linux-runtime/attachments")
    private val homeDir = File("/data/data/app/files/linux-runtime/distros/ubuntu/home")

    private fun builder() = DroidspacesCommandBuilder(EnvironmentResolver())

    private fun build(
        commandLine: String = "true",
        workingDirectory: String = "/root",
    ): List<String> = builder().build(
        prootBinary = File("/data/data/app/files/linux-runtime/bin/prisma"),
        rootfsDir = rootfsDir,
        workspaceDir = workspaceDir,
        homeDir = homeDir,
        optDir = optDir,
        tmpDir = tmpDir,
        attachmentsDir = attachmentsDir,
        command = ShellCommand(commandLine = commandLine, workingDirectory = workingDirectory),
        mounts = emptyList(),
        emulatorBinary = null,
        hostCredential = emptyMap(),
    )

    @Test
    fun guestPathIsAppliedBeforeTheLoginShell() {
        val script = build()[2]
        val path = EnvironmentResolver().runtimePath()
        val assignment = "PATH=" + ShellQuote.of(path)
        val envAt = script.indexOf(assignment)
        val shellAt = script.lastIndexOf("sh -lc")
        assertTrue("客态 PATH 必须在 sh -lc 之前由 env 注入", envAt >= 0 && shellAt > envAt)
        assertFalse(script.substring(envAt, shellAt).contains("/system/bin"))
    }

    @Test
    fun buildWrapsEverythingInSuDashC() {
        // argv 形状固定为 `su -c <script>`：脚本是第 3 个元素（下标 2），不是第 2 个。
        val argv = build()
        assertEquals(3, argv.size)
        assertEquals("su", argv[0])
        assertEquals("-c", argv[1])
        assertTrue(argv[2].isNotBlank())
    }

    @Test
    fun hostScriptRunsAsRootInTheDistroContainer() {
        val script = build()[2]
        // 外层 `su -c` 已是 UID 0，droidspaces 侧刻意不传 `-u`：它只在 main 的
        // discovery pass 里被解析，而该 pass 遇到 `run` 就 break（main.c:938-948），
        // 写在 `run` 之后既不会生效、还会污染 run_argv（= argv + optind + 1）。
        // as_user 为空时 run_in_rootfs 直接 execvp（container.c:1650-1657），即以 root 执行。
        assertTrue(script.contains("run -- "))
        assertFalse("`-u` 不得出现在 `run` 之后", script.contains("run -u"))
        // 容器名是配置里的显式值，且必须是独立词元（不能只匹配到残片）
        assertTrue(script.contains("'tianyan'"))
        assertFalse("不得再派生 tianyan-<distro> 形式的容器名", script.contains("tianyan-ubuntu"))
    }

    @Test
    fun containerLivenessIsDecidedByExitCodeNotTextParsing() {
        val script = build()[2]
        // 宿主执行环境是 Android /system/bin/sh，实测没有可用的 grep；
        // 存活判定必须走 `droidspaces --name="$NAME" pid` 的退出码，绝不能依赖外部文本工具。
        assertTrue(
            "必须用 --name=\"\$NAME\" pid 的退出码判定存活",
            script.contains("--name=\"\$NAME\" pid"),
        )
        assertFalse("不得在宿主脚本里调用 grep", script.contains("grep"))
        assertFalse("不得再依赖 show 表格输出", script.contains("show"))
    }

    @Test
    fun containerIsStartedWithoutRootfsOverrideWhenNotYetRunning() {
        val script = build()[2]
        assertTrue("必须先确保容器在跑，否则 run 会失败", script.contains("start --name="))
        // 关键：不得把 PRoot 的 rootfs 目录当 -r 塞给已有容器。
        // 用户自建容器在 Containers/<name>/container.config 里已持有自己的 rootfs 目标
        // （可能是 ext4 镜像），-r 会触发「directory and image specified simultaneously」。
        assertFalse("start 不得覆盖容器自带 rootfs", script.contains(" -r "))
        assertFalse("PRoot rootfs 路径不得出现在宿主脚本中", script.contains(rootfsDir.absolutePath))
        assertTrue("start 的原始报错必须透出，不得整体丢弃", script.contains(">/dev/null; then"))
    }

    @Test
    fun guestDirectoryViewMatchesProotBackend() {
        val script = build()[2]
        // 这五项若缺失，插件与工具链在容器内将完全不可见或与 PRoot 视图隔开
        assertTrue(script.contains("${workspaceDir.absolutePath}:/workspace"))
        assertTrue(script.contains("${homeDir.absolutePath}:/root"))
        assertTrue(script.contains("${optDir.absolutePath}:/opt/tianyan"))
        assertTrue(script.contains("${tmpDir.absolutePath}:/tmp"))
        assertTrue(script.contains("${attachmentsDir.absolutePath}:/attachments"))
    }

    @Test
    fun commandIsExecutedInRequestedWorkingDirectory() {
        val script = build(commandLine = "ls -la")[2]
        assertTrue(script.contains("cd -- "))
        assertTrue(script.contains("ls -la"))
        // 不得给 guest 命令加 `exec`：调用方传入的是任意 shell 片段，`exec` 只接受一条
        // 简单命令，会让 `a; b` 的 b 不执行、`cd` / `if` 这类内建与关键字直接失败。
        assertFalse("不得给 guest 命令加 exec 前缀", script.contains("exec ls -la"))
        assertFalse(script.contains("&& exec "))
    }

    @Test
    fun cdFailureDiagnosticNamesTargetOnStderrAndExits125() {
        val target = "/workspace/not-mounted"
        val script = build(commandLine = "true", workingDirectory = target)[2]
        // 客态命令整体再经 ShellQuote，单引号会变成 '\''，不能按未转义字面量切片。
        val cd = script.indexOf("cd -- ")
        assertTrue("必须先 cd 到目标目录", cd >= 0)
        val branchStart = script.indexOf("|| { ", cd)
        val exitAt = script.indexOf("exit 125", branchStart)
        assertTrue(branchStart > cd && exitAt > branchStart)
        val failure = script.substring(branchStart, exitAt + "exit 125".length)
        assertTrue("诊断必须带上目标目录", failure.contains(target))
        assertTrue("必须覆盖目录不存在", failure.contains("不存在"))
        assertTrue("必须覆盖未通过 -B 绑定挂载", failure.contains("未通过 -B"))
        assertTrue("诊断只能写到 stderr", failure.contains(">&2"))
        assertFalse("诊断不得写到 stdout", failure.contains(">&1"))
        assertTrue("退出码保持 125", failure.endsWith("exit 125"))
        assertFalse("不得再声称天衍从不修改容器", failure.contains("不自行创建或修改"))
        assertTrue("应说明 start 会把 -B 写回容器配置", failure.contains("写回容器配置"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun qemuCompatibilityIsRejectedInsteadOfSilentlyFailing() {
        builder().build(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            command = ShellCommand(commandLine = "true"),
            mounts = emptyList(),
            emulatorBinary = File("/qemu-x86_64"),
            hostCredential = emptyMap(),
        )
    }

    @Test
    fun disabledStorageMountIsNotForwarded() {
        val argv = builder().build(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                StorageMountBinding(
                    id = "test-disabled",
                    name = "已停用",
                    hostPath = "/storage/emulated/0/Download",
                    guestPath = "/mnt/download",
                    enabled = false,
                ),
            ),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
        assertFalse(argv[2].contains("/mnt/download"))
    }

    @Test
    fun illegalStorageMountIsSkippedNotCrashing() {
        val argv = builder().build(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                // 容器路径不在 /mnt 或 /sdcard 内 → 必须被过滤，不能注入 argv
                StorageMountBinding(
                    id = "test-illegal",
                    name = "非法路径",
                    hostPath = "/storage/emulated/0/Download",
                    guestPath = "/etc",
                ),
            ),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
        assertFalse(argv[2].contains("/etc"))
    }

    @Test
    fun interactiveSessionCarriesBannerAndGeometry() {
        val argv = builder().buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            config = SessionConfig(columns = 120, rows = 40, showBanner = true, usePty = true),
            ptyMarker = null,
            nativePty = false,
            mounts = emptyList(),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
        val script = argv[2]
        assertTrue(script.contains(DroidspacesCommandBuilder.GUEST_MOTD))
        assertTrue(script.contains("stty cols 120 rows 40"))
    }

    @Test
    fun protocolSessionStaysOnPlainPipe() {
        val argv = builder().buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            config = SessionConfig(commandLine = "/bin/sh", usePty = false, showBanner = false),
            ptyMarker = null,
            nativePty = false,
            mounts = emptyList(),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
        val script = argv[2]
        // MCP STDIO 会话不得混入 banner 与 stty，否则污染协议输出流
        assertFalse(script.contains(DroidspacesCommandBuilder.GUEST_MOTD))
        assertFalse(script.contains("stty cols"))
    }

    @Test
    fun selectorRoutesToProotUntilRootModeIsSelected() {
        runBlocking {
            val modes = MutableStateFlow(ExecutionMode.PROOT)
            val containerBackend = MutableStateFlow(false)
            val selector = ModeBackendSelector(
                proot = ProotCommandBuilder(EnvironmentResolver()),
                droidspaces = builder(),
                modes = modes,
                containerBackend = containerBackend,
            )
            // 模式取自 Flow 的当前值，不依赖任何调度器上的异步收集器，因此无竞态。
            // PRoot 使用宿主 File.absolutePath；Windows JVM 与 Android/Linux 的路径格式不同。
            val expectedProotPath = File("/data/app/proot").absolutePath
            assertEquals(expectedProotPath, sampleArgv(selector).first())

            // ROOT 模式但实验开关仍关闭：必须继续走 PRoot（对现有 ROOT 用户零行为变化）
            modes.value = ExecutionMode.ROOT
            assertEquals(expectedProotPath, sampleArgv(selector).first())

            // 只有显式打开开关，才切换到容器后端
            containerBackend.value = true
            assertEquals("su", sampleArgv(selector).first())
        }
    }

    /** 用同一组入参驱动后端，断言「同一份输入在不同模式下走不同后端」。 */
    private fun sampleArgv(selector: ModeBackendSelector): List<String> = runBlocking {
        selector.active().build(
            prootBinary = File("/data/app/proot"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            command = ShellCommand(commandLine = "true"),
            mounts = emptyList(),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
    }
}
