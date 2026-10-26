package top.tianyan.app.runtime.tools

import top.tianyan.app.core.model.RuntimeName
import top.tianyan.app.core.model.RuntimeRequirement
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.tools.DependencyManager
import top.tianyan.app.core.tools.ProviderManager
import top.tianyan.app.core.tools.ToolActionResult
import top.tianyan.app.core.tools.ToolRuntimeAdapter
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.ShellCommand
import top.tianyan.app.runtime.shell.SessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

class CodexToolInstaller(
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val providerManager: ProviderManager,
    private val remoteScriptRunner: RemoteScriptRunner,
    private val toolCommandLinker: ToolCommandLinker,
) : ToolRuntimeAdapter {
    override val toolId: String = "codex"

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        try {
            checkRuntimeReady()
            emit(InstallEvent.Progress(toolId, "准备 curl 和 CA 证书", 0.15f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            ensureDependency(RuntimeName.CURL, toolId)
            ensureDependency(RuntimeName.CA_CERTIFICATES, toolId)
            emit(InstallEvent.Progress(toolId, "运行 OpenAI Codex 安装脚本", 0.45f, InstallEvent.Phase.RUNNING_INSTALLER))
            val installEnvironment = providerManager.environment() + mapOf(
                "HOME" to ToolLayout.toolDirectory(toolId),
                "CODEX_HOME" to ToolLayout.toolDataDirectory(toolId),
            )
            val install = runCatching {
                executeAndReport(
                    remoteScriptRunner.run(
                        RemoteScriptSpec(
                            name = "codex",
                            url = "https://chatgpt.com/codex/install.sh",
                            retries = 0,
                        ),
                        installEnvironment,
                    ),
                )
            }.getOrElse { CommandResult(exitCode = 1, stdout = "", stderr = it.message ?: "网络超时", durationMs = 0L) }

            if (!install.isSuccess) {
                // 失败必须如实上抛。此前这里会写入一个只会打印 "codex 0.1.0" 的 stub
                // 脚本充当伪 CLI，导致下方 verify 必过、安装被记为 Completed/INSTALLED——
                // 真实 CLI 从未落盘却被掩盖，网络恢复后也不会触发重装。
                val reason = install.stderr.ifBlank { install.stdout }.trim()
                error(
                    "Codex 远程安装脚本执行失败（exit=${install.exitCode}）：" +
                        reason.lineSequence().firstOrNull().orEmpty().ifBlank { "网络受限或脚本下载失败" },
                )
            }
            val link = toolCommandLinker.link(
                command = "codex",
                target = "${ToolLayout.toolDirectory(toolId)}/.local/bin/codex",
                environment = providerManager.environment(),
            )
            if (!link.isSuccess) error(link.stderr.ifBlank { "无法创建 codex 命令入口" })
            emit(InstallEvent.Progress(toolId, "验证 codex 命令", 0.85f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val version = executeAndReport("codex --version")
            if (!version.isSuccess) error(version.stderr.ifBlank { "找不到 codex 命令" })
            emit(InstallEvent.Completed(toolId, version.stdout.trim().lineSequence().firstOrNull()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "Codex 安装失败"))
        }
    }

    override suspend fun launch(): CommandResult = execute("codex")

    override suspend fun verify(): CommandResult = execute("codex --version")

    override suspend fun interactiveSessionConfig(): SessionConfig = SessionConfig(
        commandLine = "exec codex",
        environment = providerManager.environment(),
        allowSttyResize = false,
    )

    override suspend fun uninstall(deleteData: Boolean): ToolActionResult {
        val dataCleanup = if (deleteData) " && rm -rf ${ToolLayout.toolDataDirectory(toolId)}" else ""
        val link = toolCommandLinker.remove("codex", providerManager.environment())
        val directory = execute("rm -rf ${ToolLayout.toolDirectory(toolId)}$dataCleanup")
        return ToolActionResult(
            success = link.isSuccess && directory.isSuccess,
            message = listOf(link, directory).firstOrNull { !it.isSuccess }
                ?.let { it.stderr.ifBlank { it.stdout } }
                ?.ifBlank { "卸载失败" }
                ?: "卸载完成",
        )
    }

    private suspend fun ensureDependency(name: RuntimeName, toolId: String) {
        val result = dependencyManager.acquire(RuntimeRequirement(name), toolId)
        if (result.isFailure) error(result.errorOrNull()?.message ?: "依赖安装失败：$name")
    }

    private suspend fun execute(command: String) = linuxRuntime.execute(
        ShellCommand(command, environment = providerManager.environment()),
    )

    private suspend fun FlowCollector<InstallEvent>.executeAndReport(
        result: CommandResult,
    ): CommandResult {
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("codex", it)) }
        result.stderr.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("codex", it)) }
        return result
    }

    private suspend fun FlowCollector<InstallEvent>.executeAndReport(
        command: String,
    ): CommandResult = executeAndReport(execute(command))

    private fun checkRuntimeReady() {
        check(linuxRuntime.state.value is RuntimeState.Ready) {
            "Linux Runtime 未就绪，请先初始化 Linux"
        }
    }

    private fun CommandResult.toActionResult() = ToolActionResult(
        success = isSuccess,
        message = stderr.ifBlank { stdout }.trim().ifBlank { if (isSuccess) "卸载完成" else "命令退出码 $exitCode" },
    )
}
