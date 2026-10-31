package top.tianyan.app.harness.environment

import top.tianyan.app.harness.HarnessPathResolver
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.harness.core.SessionResourceScope
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.*
import java.util.UUID
import kotlinx.coroutines.withContext

/** The only production composition point for files and processes. Replace this factory as a whole. */
class LocalExecutionEnvironmentFactory(
    private val runtime: LinuxRuntime,
    private val files: WorkspaceFileAccess,
    private val paths: HarnessPathResolver,
    private val downloader: top.tianyan.app.core.network.FileDownloader? = null,
    private val buildScripts: top.tianyan.app.core.database.BuildScriptRepository? = null,
    private val variables: top.tianyan.app.runtime.LinuxEnvironmentManager? = null,
) : ExecutionEnvironmentFactory {
    override suspend fun open(owner: String, workspace: String, distribution: String?): ExecutionEnvironment {
        val cwd = workspace.ifBlank { "/workspace" }
        require(cwd == "/workspace" || cwd.startsWith("/workspace/") || !cwd.startsWith('/')) {
            "文件工作区必须位于 /workspace"
        }
        require(cwd.split('/').none { it == ".." } && '\u0000' !in cwd) { "非法工作区路径" }
        val distro = (distribution?.takeIf { it.isNotBlank() } ?: runtime.activeDistroId.value).trim().lowercase()
        val root = runtime.workspacePath().canonicalFile
        require(files.workspaceLockKey() == root.absolutePath) { "文件系统与命令运行时的工作区根不一致" }
        val operations = files.withBase(cwd)
        val guestCwd = paths.resolveWorkingDirectory(null, cwd)
        val expected = java.io.File(root, guestCwd.removePrefix("/workspace").trimStart('/')).canonicalFile
        require(operations.workspaceLockKey() == expected.absolutePath) { "工作区映射越界或不可用" }
        val commands = BoundLinuxCommands(runtime, distro, "agent-process:${UUID.randomUUID()}:")
        return LocalEnvironment(
            ExecutionEnvironmentId("local-proot", distro, guestCwd, owner),
            operations, commands, runtime, paths, downloader?.let { LocalExecutionDownloads(it, operations) },
            buildScripts?.let(::LocalExecutionBuildBindings),
            variables,
        )
    }
}

private class LocalEnvironment(
    override val id: ExecutionEnvironmentId,
    override val files: WorkspaceFileAccess,
    private val commands: BoundLinuxCommands,
    private val runtime: LinuxRuntime,
    private val paths: HarnessPathResolver,
    override val downloads: ExecutionDownloads?,
    override val buildBindings: ExecutionBuildBindings?,
    private val variables: top.tianyan.app.runtime.LinuxEnvironmentManager?,
) : ExecutionEnvironment, LocalTerminalLaunch {
    override val artifacts = LocalExecutionArtifacts(files)
    private val resources = SessionResourceScope()
    private val background = LocalBackgroundProcesses(commands, resources)
    override suspend fun outputSecrets() = resources.activity { variables?.redactionSecrets(id.distribution).orEmpty() }
    override val supportsSharedLocalServices get() = runtime.activeDistroId.value == id.distribution
    override fun workingDirectory(explicit: String?) = paths.resolveWorkingDirectory(explicit, id.workspace)
    override suspend fun execute(command: ShellCommand) = resources.activity {
        withContext(ExecutionEnvironmentContext(this@LocalEnvironment, resources::own)) { commands.execute(command) }
    }
    override suspend fun startBackground(id: String, command: ShellCommand) = background.start(id, command)
    override suspend fun stopBackground(id: String) = background.stop(id)
    override fun listBackground() = commands.listBackground()
    override fun getBackgroundLogs(id: String) = commands.getBackgroundLogs(id)
    override suspend fun openSession(config: SessionConfig) = resources.activity {
        OwnedLinuxSession.open(resources) { runtime.startSession(config, id.distribution) }
    }
    override suspend fun openTerminal(config: SessionConfig) = openSession(config)
    override suspend fun terminalLaunch(config: SessionConfig) = resources.activity {
        runtime.buildInteractiveLaunch(config, id.distribution)
    }
    override suspend fun close() = resources.close()
}
