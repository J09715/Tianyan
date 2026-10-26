package top.tianyan.app.runtime.doctor

import top.tianyan.app.core.model.SandboxToolchainCatalog
import top.tianyan.app.core.model.ToolchainProbe
import top.tianyan.app.core.model.ToolchainReport
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.ShellCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 🔬 沙箱工具链检测器
 *
 * 对 [SandboxToolchainCatalog] 里的每个探针跑一条 shell 命令，一次性拿到
 * 「工具是否存在 + 版本号」，交给纯逻辑 [ToolchainVersion] 判定状态。
 *
 * 关键口径（review 第 4 条）：
 * - 沙箱不可达（execute 抛异常）、命令超时（exitCode 124）、非 0 退出、
 *   stdout 被截断导致某个探针缺少 PATH/VER 标记 → 该工具一律判 UNKNOWN，绝不误报 MISSING。
 * - 每条版本命令外面包 `timeout 5`，串行 15 个探针（含 jadx/apktool 等 JVM --version）
 *   总超时给到 60 秒，避免 PRoot 里被误判超时。
 */
class ToolchainInspector(
    private val linuxRuntime: LinuxRuntime,
) {
    private val cacheMutex = Mutex()

    @Volatile
    private var cached: ToolchainReport? = null

    private val _report = MutableStateFlow<ToolchainReport?>(null)
    val report: StateFlow<ToolchainReport?> = _report.asStateFlow()

    /** 未过期的上次报告；过期或不存在时返回 null。 */
    fun cachedIfFresh(now: Long = System.currentTimeMillis()): ToolchainReport? {
        val hit = cached ?: return null
        return hit.takeIf { now - it.checkedAt < CACHE_TTL_MS }
    }

    /**
     * 全量探针。[force] 为 false 且缓存未超过 [CACHE_TTL_MS] 时直接返回上次报告。
     * 结果写入 [report]，供仍在前台的界面观察；补齐结束后用 force 刷新。
     */
    suspend fun inspect(force: Boolean = false): ToolchainReport = cacheMutex.withLock {
        if (!force) {
            cachedIfFresh()?.let { hit ->
                if (_report.value != hit) _report.value = hit
                return@withLock hit
            }
        }
        val fresh = probe()
        cached = fresh
        _report.value = fresh
        fresh
    }

    private suspend fun probe(): ToolchainReport = withContext(Dispatchers.IO) {
        val probes = SandboxToolchainCatalog.probes
        if (probes.isEmpty()) return@withContext ToolchainReport()

        val raw = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = buildProbeScript(probes),
                    timeoutMs = PROBE_TIMEOUT_MS,
                )
            )
        }.getOrNull()

        // 沙箱不可达 / 执行抛异常：全部如实 UNKNOWN
        if (raw == null) {
            return@withContext ToolchainReport(
                results = probes.map { ToolchainProbeParser.unknown(it) },
            )
        }

        // 命令超时或非 0 退出：全部 UNKNOWN，不误报缺失
        if (raw.exitCode != 0) {
            return@withContext ToolchainReport(
                results = probes.map { ToolchainProbeParser.unknown(it) },
            )
        }

        val parsed = ToolchainProbeParser.parse(raw.stdout, probes)
        val results = probes.map { probe -> parsed[probe.id] ?: ToolchainProbeParser.unknown(probe) }
        ToolchainReport(results = results)
    }

    /**
     * 生成合并探针脚本。
     *
     * 输出约定：每个探针输出两行：
     * ```
     * __TX__<id>__PATH__<命中路径|空>
     * __TX__<id>__VER__<版本原文|空>
     * ```
     */
    private fun buildProbeScript(probes: List<ToolchainProbe>): String {
        val sb = StringBuilder()
        probes.forEach { probe ->
            // 1) 定位可执行文件：候选路径优先，再退 PATH 查找
            val resolve = StringBuilder()
            resolve.append("res=\"\"; ")
            probe.candidatePaths.forEach { p ->
                resolve.append("if [ -z \"\$res\" ] && { [ -x \"$p\" ] || [ -f \"$p\" ]; }; then res=\"$p\"; fi; ")
            }
            probe.commands.forEach { cmd ->
                resolve.append("if [ -z \"\$res\" ]; then c=\$(command -v $cmd 2>/dev/null || true); [ -n \"\$c\" ] && res=\"\$c\"; fi; ")
            }
            resolve.append("echo \"__TX__${probe.id}__PATH__\$res\"; ")

            // 2) 取版本（仅当定位成功），外层加 timeout 5 防单个工具卡死拖垮整轮
            val versionCmd = probe.versionCommand
            val version = if (versionCmd == null) {
                "echo \"__TX__${probe.id}__VER__\"; "
            } else {
                val shellCmd = versionCmd
                    .replace("{cmd}", "\$res")
                    .replace("{path}", "\$res")
                // 注意：sh -c 必须用双引号包裹，否则内层 shell 看不到外层展开的 $res（实测踩坑：
                // 单引号写法导致 CMake/Ninja 版本探测输出 "--version: not found" 而被误判待确认）
                "if [ -n \"\$res\" ]; then v=\$(timeout 5 sh -c \"$shellCmd\" 2>&1 | head -2 | tr '\\n' ' '); echo \"__TX__${probe.id}__VER__\$v\"; else echo \"__TX__${probe.id}__VER__\"; fi; "
            }

            sb.append(resolve).append(version)
        }
        return sb.toString()
    }

    companion object {
        /** 聊天页反复进入时复用上次报告，避免每次都跑完整 15 项探针。 */
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        // 15 个探针串行，含 jadx/apktool 等 JVM --version，给足 60 秒避免误判
        private const val PROBE_TIMEOUT_MS = 60_000L
    }
}
