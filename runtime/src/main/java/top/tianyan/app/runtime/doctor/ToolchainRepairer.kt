package top.tianyan.app.runtime.doctor

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.core.model.BuiltinPluginBundles
import top.tianyan.app.core.model.RepairStrategy
import top.tianyan.app.core.model.ToolchainReport
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.ShellCommand

/**
 * 沙箱工具链补齐（进程级，不跟聊天页 ViewModel 走）。
 *
 * 离开聊天会取消 viewModelScope，[top.tianyan.app.runtime.shell.ProcessShellExecutor]
 * 会 destroyForcibly 正在跑的 apt/dpkg。编排因此放在本类自己的 [scope] 里：
 * 先装开发套件组件，再在 [ToolchainInstalls.runExclusive] 里装 apt 包。
 * 已有补齐在跑时 [start] 直接拒绝，避免两套 apt 叠在一起。
 */
class ToolchainRepairer(
    private val linuxRuntime: LinuxRuntime,
    private val installs: ToolchainInstalls,
    private val inspector: ToolchainInspector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val gate = AtomicBoolean(false)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _outcome = MutableStateFlow<RepairOutcome?>(null)
    val outcome: StateFlow<RepairOutcome?> = _outcome.asStateFlow()

    /** @return 已有任务在跑时返回 false，不重置日志、也不再开一轮。 */
    fun start(report: ToolchainReport): Boolean {
        if (!gate.compareAndSet(false, true)) return false
        _logs.value = emptyList()
        _outcome.value = null
        _running.value = true
        scope.launch {
            try {
                orchestrate(report)
            } finally {
                _running.value = false
                gate.set(false)
            }
        }
        return true
    }

    fun aptTargets(report: ToolchainReport): List<Pair<String, List<String>>> =
        report.repairable.mapNotNull { result ->
            val apt = result.probe.repair as? RepairStrategy.ByAptPackages ?: return@mapNotNull null
            result.probe.id to apt.packages
        }

    private suspend fun orchestrate(report: ToolchainReport) {
        val bundleIds = report.repairable
            .mapNotNull { it.probe.repair as? RepairStrategy.ByBundleComponents }
            .flatMap { it.componentIds }
            .distinct()
            .toSet()
        if (bundleIds.isNotEmpty()) {
            log("==> [1/2] 安装开发套件组件: ${bundleIds.joinToString(", ")}")
            try {
                installs.installComponents(bundleIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log("套件安装失败: ${error.message}")
            }
        }

        val targets = aptTargets(report)
        val outcome = if (targets.isEmpty()) {
            RepairOutcome(emptyList(), emptyList())
        } else {
            log("==> [2/2] 安装 apt 工具: ${targets.joinToString(", ") { it.first }}")
            installs.runExclusive { repairApt(targets) }
        }
        _outcome.value = outcome
        if (outcome.failures.isNotEmpty()) {
            log("补齐完成，${outcome.failures.size} 项失败（详见上方日志）")
        }
        runCatching { inspector.inspect(force = true) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                log("补齐后重新检测失败: ${error.message}")
            }
    }

    private suspend fun repairApt(targets: List<Pair<String, List<String>>>): RepairOutcome {
        val aptOpts = BuiltinPluginBundles.bundleAptOptions()
        val installed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        log("==> [工具链补齐] 执行 PRoot 准备步骤（复用开发套件 PluginBundleScripts）")
        BuiltinPluginBundles.bundlePreparationSteps().forEach { step ->
            val result = linuxRuntime.execute(ShellCommand(commandLine = step, timeoutMs = STEP_TIMEOUT_MS))
            if (!result.isSuccess) {
                log("准备步骤告警: ${result.stderr.ifBlank { result.stdout }.takeLast(200)}")
            }
        }
        log("==> apt-get update")
        linuxRuntime.execute(ShellCommand(commandLine = aptUpdateLine(aptOpts), timeoutMs = STEP_TIMEOUT_MS))
        targets.forEach { (probeId, packages) ->
            packages.forEach { pkg ->
                log("==> 安装 $pkg")
                val result = linuxRuntime.execute(
                    ShellCommand(commandLine = aptInstallLine(aptOpts, pkg), timeoutMs = INSTALL_TIMEOUT_MS),
                )
                if (result.isSuccess) {
                    installed.add(probeId)
                    log("[OK] $pkg")
                } else {
                    val detail = result.stderr.ifBlank { result.stdout }.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toList()
                        .takeLast(3)
                        .joinToString(" | ")
                    failures.add("$pkg: $detail")
                    log("[FAIL] $pkg → $detail")
                }
            }
        }
        return RepairOutcome(installed.distinct(), failures)
    }

    private fun log(line: String) {
        _logs.value = _logs.value + line
    }

    data class RepairOutcome(
        val installedProbeIds: List<String>,
        val failures: List<String>,
    ) {
        val isSuccess: Boolean get() = failures.isEmpty()
    }

    companion object {
        private const val STEP_TIMEOUT_MS = 120_000L
        private const val INSTALL_TIMEOUT_MS = 600_000L

        /** 准备步骤之后只 update 一次，再按包安装（失败时 -f install 再试一次）。 */
        fun aptCommandLines(aptOpts: String, packages: List<String>): List<String> =
            listOf(aptUpdateLine(aptOpts)) + packages.map { aptInstallLine(aptOpts, it) }

        fun aptUpdateLine(aptOpts: String): String =
            "DEBIAN_FRONTEND=noninteractive apt-get $aptOpts update -y || true"

        fun aptInstallLine(aptOpts: String, pkg: String): String =
            "DEBIAN_FRONTEND=noninteractive apt-get $aptOpts install -y --no-install-recommends $pkg || { " +
                "DEBIAN_FRONTEND=noninteractive apt-get $aptOpts -f install -y --no-install-recommends && " +
                "DEBIAN_FRONTEND=noninteractive apt-get $aptOpts install -y --no-install-recommends $pkg; }"
    }
}
