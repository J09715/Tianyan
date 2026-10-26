package top.tianyan.app.runtime.proot

import top.tianyan.app.runtime.shell.ShellCommand
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.EnvironmentResolver
import top.tianyan.app.core.model.StorageMountBinding
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ProotCommandBuilderTest {

    @Test
    fun buildAddsQemuOnlyWhenAnExecutableEmulatorIsProvided() {
        val emulator = Files.createTempFile("qemu-x86_64", ".bin").toFile().apply {
            setExecutable(true)
        }
        try {
            val args = ProotCommandBuilder(EnvironmentResolver()).build(
                prootBinary = File("/p"),
                rootfsDir = File("/compat/rootfs"),
                workspaceDir = File("/w"),
                command = ShellCommand(commandLine = "true"),
                emulatorBinary = emulator,
            )
            val index = args.indexOf("-q")
            assert(index >= 0)
            assertEquals(emulator.absolutePath, args[index + 1])
        } finally {
            emulator.delete()
        }
    }

    @Test
    fun buildProducesProotArgsAndShellCommand() {
        val builder = ProotCommandBuilder(EnvironmentResolver())
        val args = builder.build(
            prootBinary = File("/data/data/app/bin/proot"),
            rootfsDir = File("/data/data/app/rootfs"),
            workspaceDir = File("/data/data/app/workspace"),
            command = ShellCommand(commandLine = "cat /etc/os-release"),
        )

        assertEquals(File("/data/data/app/bin/proot").absolutePath, args[0])
        assert(args.contains("--kill-on-exit"))
        assert(args.contains("--link2symlink"))
        assert(args.contains("-L"))
        assert(args.contains("--sysvipc"))
        assert(args.contains("--kernel-release=6.17.0-Tianyan"))
        assert(args.contains("--change-id=0:0"))
        val l2sBackingStore = File(File("/data/data/app/rootfs"), ".l2s").absolutePath
        assertBinding(args, "$l2sBackingStore:$l2sBackingStore")
        assertBinding(args, "/dev")
        assertBinding(args, "/proc")
        assertBinding(args, "/sys")
        assertBinding(args, "${File("/data/data/app/tmp").absolutePath}:/tmp")
        assertBinding(args, "${File("/data/data/app/workspace").absolutePath}:/workspace")
        assertBinding(args, "${File("/data/data/app/home").absolutePath}:/root")
        assertBinding(args, "${File("/data/data/app/opt/tianyan").absolutePath}:/opt/tianyan")
        val workingDirectoryIndex = args.indexOf("-w")
        assertEquals("/root", args[workingDirectoryIndex + 1])
        assertEquals("/bin/sh", args[workingDirectoryIndex + 2])
        assertEquals("-lc", args[workingDirectoryIndex + 3])
        assert(args[workingDirectoryIndex + 4].contains("export HOME='/root'"))
        assert(args[workingDirectoryIndex + 4].contains("export TMPDIR='/tmp'"))
        assert(args[workingDirectoryIndex + 4].contains("cat /etc/os-release"))
    }

    @Test
    fun buildIncludesCustomEnvironmentBeforeCommand() {
        val builder = ProotCommandBuilder(EnvironmentResolver())
        val args = builder.build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(
                commandLine = "echo hi",
                environment = mapOf("FOO" to "bar"),
            ),
        )

        assert(args.last().contains("export FOO='bar'"))
    }

    @Test
    fun interactiveBuildKeepsQuotedCommandLineIntactOnNativePty() {
        val commandLine = "stty raw -echo; exec 'python3' -u '/opt/tianyan/scripts/codegraph_mcp_server.py' --repository '/workspace'"
        val args = ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(
                commandLine = commandLine,
                allowSttyResize = false,
            ),
            nativePty = true,
        )

        // 原生 PTY 路径下命令串直接交给 bash -lc，单层解析，禁止任何引号二次转义
        assert(args.last().contains("exec 'python3' -u '/opt/tianyan/scripts/codegraph_mcp_server.py'"))
        assert(!args.last().contains("\\\""))
    }

    @Test
    fun interactiveBuildEscapesQuotesForScriptFallback() {
        val commandLine = "exec 'python3' -u '/opt/x.py'"
        val args = ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(
                commandLine = commandLine,
                allowSttyResize = false,
            ),
            ptyMarker = "/opt/tianyan/.pty-12345678",
        )

        // fallback 路径命令嵌入 script -qfec '...' 单引号内，必须用 '\'' 转义内层单引号
        assert(args.last().contains("exec 'python3' -u '/opt/x.py'"))
        assert(args.last().contains("'\\''"))
    }

    @Test
    fun interactiveBuildUsesAdapterCommandAndEnvironment() {
        val args = ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(
                commandLine = "export PATH=/root/.local/bin:\$PATH && exec codex",
                environment = mapOf("OPENAI_API_KEY" to "secret"),
                allowSttyResize = false,
            ),
            ptyMarker = "/opt/tianyan/.pty-12345678",
        )

        assert(args.last().contains("exec codex"))
        assert(args.last().contains("export OPENAI_API_KEY='secret'"))
        assert(args.last().contains("/root/.local/bin:\$PATH"))
        assert(args.last().contains("tty > /opt/tianyan/.pty-12345678"))
        assert(args.contains("-L"))
        val l2sBackingStore = File(File("/r"), ".l2s").absolutePath
        assertBinding(args, "$l2sBackingStore:$l2sBackingStore")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafePtyMarker() {
        ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(),
            ptyMarker = "/tmp/unsafe",
        )
    }

    @Test
    fun doesNotImplicitlyMountSharedStorage() {
        val args = ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(commandLine = "true"),
            mounts = emptyList(),
        )

        assert(args.none { it.contains(":/sdcard") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMountOverCriticalGuestPath() {
        ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                StorageMountBinding("bad", "bad", "/storage/emulated/0", "/root", enabled = true),
            ),
        )
    }

    @Test
    fun skipsUnreadableStorageMountGracefully() {
        val args = ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                StorageMountBinding("download", "下载", "/storage/emulated/0/Download", "/sdcard/Download", enabled = true),
            ),
        )

        assert(args.none { it.contains(":/sdcard/Download") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMountEscapingSharedStorageRoot() {
        ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                StorageMountBinding("escape", "escape", "/data/data/top.tianyan.app", "/sdcard/escape", enabled = true),
            ),
        )
    }

    private fun assertBinding(args: List<String>, binding: String) {
        val index = args.indexOf(binding)
        assert(index > 0)
        assertEquals("-b", args[index - 1])
    }

    @Test
    fun hostCredentialIsInjectedLastAndOverridesProviderEnvironment() {
        val args = ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(
                commandLine = "tianyan-host health",
                environment = mapOf("TIANYAN_BRIDGE_TOKEN" to "provider-forged"),
            ),
            hostCredential = mapOf(
                "TIANYAN_BRIDGE_TOKEN" to "native-issued",
                "TIANYAN_BRIDGE_ACTIONS" to "host.install-apk,host.logcat",
            ),
        )

        val resolved = args.last()
        assert(!resolved.contains("provider-forged"))
        assert(resolved.contains("export TIANYAN_BRIDGE_TOKEN='native-issued'"))
        assert(resolved.contains("export TIANYAN_BRIDGE_ACTIONS='host.install-apk,host.logcat'"))
    }

    @Test
    fun interactiveBuildAlsoCarriesHostCredential() {
        val args = ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(commandLine = "/bin/bash -i"),
            nativePty = true,
            hostCredential = mapOf("TIANYAN_BRIDGE_TOKEN" to "native-issued"),
        )

        assert(args.last().contains("export TIANYAN_BRIDGE_TOKEN='native-issued'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIllegalHostCredentialKeyNames() {
        ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(commandLine = "true"),
            hostCredential = mapOf("BAD-NAME" to "value"),
        )
    }
}
