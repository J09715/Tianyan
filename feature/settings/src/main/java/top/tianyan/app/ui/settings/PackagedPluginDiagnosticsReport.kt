package top.tianyan.app.ui.settings

import java.time.Instant
import top.tianyan.app.core.database.InstallLogEntity
import top.tianyan.app.plugin.packages.PackagedPluginEntry

internal fun buildPluginDiagnostics(entry: PackagedPluginEntry, logs: List<InstallLogEntity>): String {
    val manifest = entry.manifest
    val spec = manifest.plugin
    val capabilities = (
        manifest.permissions.map { it } +
            spec?.capabilities.orEmpty().map { it.name + it.target.takeIf(String::isNotBlank)?.let { target -> ":$target" }.orEmpty() }
        ).distinct()
    val dependencies = spec?.dependencies.orEmpty().map {
        "${it.id} [${it.minimumVersion}, ${it.maximumVersionExclusive ?: "∞"})"
    }.ifEmpty { manifest.dependencies }
    return buildString {
        appendLine("Tianyan 插件诊断报告")
        appendLine("名称: ${entry.name}")
        appendLine("工具 ID: ${entry.toolId}")
        appendLine("插件 ID: ${entry.descriptor.id}")
        appendLine("目录版本: ${entry.descriptor.version}")
        appendLine("安装版本: ${entry.installedVersion ?: "未安装"}")
        appendLine("状态: ${entry.state}")
        appendLine("来源: ${entry.source}")
        appendLine("插件 API: ${entry.descriptor.api.minimum}..${entry.descriptor.api.maximum}")
        appendLine("执行形态: ${spec?.execution ?: "LEGACY"}")
        spec?.webSource?.let { appendLine("Web 来源: $it") }
        spec?.serviceToolId?.let { appendLine("服务工具: $it") }
        appendLine("扩展: ${entry.descriptor.extensions.joinToString { "${it.id}/${it.point}" }.ifEmpty { "无" }}")
        appendLine("能力: ${capabilities.joinToString(" | ").ifEmpty { "无" }}")
        appendLine("依赖: ${dependencies.joinToString(" | ").ifEmpty { "无" }}")
        appendLine("完整性条目: ${spec?.integrity?.size ?: 0}")
        appendLine("资源:")
        val resources = spec?.resources.orEmpty()
        if (resources.isEmpty()) {
            appendLine("  (无)")
        } else {
            resources.forEach { appendLine("  ${it.key} ${it.path} ${it.sharing}/${it.removal}${it.contentSha256?.let { hash -> " $hash" }.orEmpty()}") }
        }
        spec?.harness?.let { harness ->
            appendLine("Harness 入口: ${harness.entry}")
            appendLine("Harness 扩展: 工具 ${harness.tools.size} | 节点 ${harness.nodes.size} | Provider ${harness.providers.size}")
        }
        if (manifest.commandLinks.isNotEmpty()) appendLine("命令入口: ${manifest.commandLinks.joinToString(" | ")}")
        manifest.launchCommand?.let { appendLine("启动命令: $it") }
        manifest.verifyCommand?.let { appendLine("验证命令: $it") }
        appendLine("安装步骤: ${manifest.installSteps.size} 条")
        appendLine("最近安装日志:")
        val recent = logs.takeLast(40)
        if (recent.isEmpty()) {
            appendLine("  (无)")
        } else {
            recent.forEach { log ->
                appendLine("  ${Instant.ofEpochMilli(log.createdAt)} [${log.event}] ${log.message}")
            }
        }
    }
}
