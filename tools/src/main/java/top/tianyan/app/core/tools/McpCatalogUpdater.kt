package top.tianyan.app.core.tools

import top.tianyan.app.core.model.McpCatalogEntry
import top.tianyan.app.core.model.McpUpdateStatus
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.ShellCommand

/**
 * MCP 能力仓库的「检查更新 / 一键升级」执行器。
 *
 * 设计上把联网与包解析全部留在装配脚本里（沙箱内唯一有 `npm` 的地方），本层只做三件事：
 * 拼命令、把 `key=value` 输出解析成 [McpUpdateStatus]、把失败翻译成一句人话。
 * 这样「上游版本从哪来」只有一处实现，Kotlin 侧不重复一遍 registry 语义。
 *
 * **升级为什么是安全的**：装配脚本先取镜像上最新版本对应的 `dist.integrity`，安装成功后
 * 把 `SPEC` 与 `INTEGRITY` 一起写进落位标记；运行期的启动命令从标记里读版本，
 * 因此升级立即生效，而任何一次「按标记重装」都会拿落盘校验和比对，上游偷改包会被挡住。
 */
class McpCatalogUpdater(private val linuxRuntime: LinuxRuntime) {

    /** 查询一条能力：已装版本（落位标记）× 上游最新版本（镜像）。 */
    suspend fun check(entry: McpCatalogEntry): McpUpdateStatus = check(entry, linuxRuntime.activeDistroId.value)

    private suspend fun check(entry: McpCatalogEntry, distroId: String): McpUpdateStatus {
        if (!entry.installable) return unsupported(entry)
        val result = runCatching { run(entry.checkUpdateCommand, CHECK_TIMEOUT_MS, distroId) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; return McpUpdateStatus(entry.id, error = "无法访问本机 Linux 运行时：${it.message.orEmpty()}") }
        val fields = parseFields(result.stdout)
        val installed = fields["INSTALLED"].orEmpty()
        val error = fields["ERROR"].orEmpty()
        if (!result.isSuccess || error.isNotBlank()) {
            return McpUpdateStatus(entry.id, installedVersion = installed, error = error.ifBlank { errorLine(result) })
        }
        return McpUpdateStatus(entry.id, installedVersion = installed, latestVersion = fields["LATEST"].orEmpty())
    }

    /** 返回安装脚本已复核的版本；不以额外联网查询决定升级成败。 */
    suspend fun upgrade(entry: McpCatalogEntry): McpUpdateStatus {
        if (!entry.installable) return unsupported(entry)
        val distroId = linuxRuntime.activeDistroId.value
        val result = runCatching { run(entry.upgradeCommand, UPGRADE_TIMEOUT_MS, distroId) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; return McpUpdateStatus(entry.id, error = "无法访问本机 Linux 运行时：${it.message.orEmpty()}") }
        if (!result.isSuccess) {
            return McpUpdateStatus(entry.id, error = errorLine(result).ifBlank { "升级失败，请查看安装日志" })
        }
        val fields = parseFields(result.stdout)
        val installed = fields["INSTALLED"].orEmpty()
        if (installed.isBlank() || fields["CHANGED"] !in setOf("0", "1"))
            return McpUpdateStatus(entry.id, error = "升级结果缺少已验证的版本信息，请检查运行时脚本")
        if (fields["CHANGED"] == "1") top.tianyan.app.core.common.McpCatalogRevisions.upgraded(distroId, entry.id)
        return McpUpdateStatus(entry.id, installedVersion = installed, latestVersion = fields["LATEST"].orEmpty())
    }

    private suspend fun run(commandLine: String, timeoutMs: Long, distroId: String): CommandResult {
        // 命令由 catalog 常量拼成；这里再兜一道白名单，防止将来有人把动态输入接进来。
        require(SAFE_COMMAND.matches(commandLine)) { "拒绝执行未登记的 MCP 仓库命令：$commandLine" }
        return linuxRuntime.execute(
            ShellCommand(
                commandLine = commandLine,
                workingDirectory = "/root",
                timeoutMs = timeoutMs,
            ),
            distroId = distroId,
        )
    }

    private fun unsupported(entry: McpCatalogEntry): McpUpdateStatus = McpUpdateStatus(
        entry.id,
        error = entry.unavailableReason.ifBlank { "该能力当前不可安装，无法检查更新" },
    )

    /** 解析脚本输出的 `KEY=VALUE` 行；`-` 视为空值。 */
    private fun parseFields(stdout: String): Map<String, String> =
        stdout.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim().takeIf { it != "-" }.orEmpty()
                key to value
            }
            .toMap()

    /** 脚本的失败信息统一打成 `[MCP] 错误：…`，取第一句给用户看，不要甩整段 stderr。 */
    private fun errorLine(result: CommandResult): String =
        (result.stderr + "\n" + result.stdout).lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(ERROR_PREFIX) }
            ?.removePrefix(ERROR_PREFIX)
            ?: result.stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

    private companion object {
        const val ERROR_PREFIX = "[MCP] 错误："

        /** 只查 registry，两次网络往返；给足超时但不至于让用户等成卡死。 */
        const val CHECK_TIMEOUT_MS = 45_000L

        /** 全局安装要下载并展开 tarball，PRoot 下比裸机慢得多。 */
        const val UPGRADE_TIMEOUT_MS = 300_000L

        /** 只允许装配脚本的 check / upgrade 子命令，包名段限 npm 合法字符。 */
        val SAFE_COMMAND = Regex(
            "^/bin/sh /opt/tianyan/scripts/setup_mcp_catalog\\.sh " +
                "(check|upgrade) [A-Za-z0-9@/._+-]+( [A-Za-z0-9@/._+-]+)*$",
        )
    }
}
