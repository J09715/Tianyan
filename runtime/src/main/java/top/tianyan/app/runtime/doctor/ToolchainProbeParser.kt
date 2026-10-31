package top.tianyan.app.runtime.doctor

import top.tianyan.app.core.model.ToolchainProbe
import top.tianyan.app.core.model.ToolchainProbeResult
import top.tianyan.app.core.model.ToolchainStatus
import top.tianyan.app.core.model.ToolchainVersion

/**
 * 解析合并探针脚本的 stdout。
 *
 * 每个探针应有 PATH 与 VER 两行标记。缺任一标记（输出被截断、命令中途失败）一律 UNKNOWN，
 * 不把「没读到」误判成工具缺失。
 */
internal object ToolchainProbeParser {
    private const val TAG = "__TX__"
    private const val PATH_MARK = "__PATH__"
    private const val VER_MARK = "__VER__"

    fun parse(stdout: String, probes: List<ToolchainProbe>): Map<String, ToolchainProbeResult> {
        val pathById = mutableMapOf<String, String>()
        val verById = mutableMapOf<String, String>()

        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(TAG) }
            .forEach { line ->
                val rest = line.removePrefix(TAG)
                val kind = if (rest.contains(PATH_MARK)) PATH_MARK else VER_MARK
                val idx = rest.indexOf(kind)
                if (idx <= 0) return@forEach
                val id = rest.substring(0, idx)
                val value = rest.substring(idx + kind.length).trim()
                if (id.isBlank()) return@forEach
                if (kind == PATH_MARK) pathById[id] = value else verById[id] = value
            }

        val out = mutableMapOf<String, ToolchainProbeResult>()
        probes.forEach { probe ->
            if (!pathById.containsKey(probe.id) || !verById.containsKey(probe.id)) {
                out[probe.id] = unknown(probe)
                return@forEach
            }
            val resolved = pathById[probe.id]?.takeIf { it.isNotBlank() }
            val version = ToolchainVersion.extractVersion(verById[probe.id])
            val status = ToolchainVersion.resolveStatusWithRoot(probe, resolved, version, reachable = true)
            out[probe.id] = ToolchainProbeResult(
                probe = probe,
                resolvedPath = resolved,
                version = version,
                status = status,
                summary = "",
            )
        }
        return out
    }

    fun unknown(probe: ToolchainProbe): ToolchainProbeResult = ToolchainProbeResult(
        probe = probe,
        status = ToolchainStatus.UNKNOWN,
        summary = "",
    )
}
