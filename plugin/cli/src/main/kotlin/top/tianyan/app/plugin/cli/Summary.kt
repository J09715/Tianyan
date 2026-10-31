package top.tianyan.app.plugin.cli

import java.io.PrintStream
import java.util.Locale
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.WebPluginPackagePolicy

internal object Summary {
    fun print(out: PrintStream, manifest: ToolManifest, files: Collection<ContentFile>) {
        val spec = requireNotNull(manifest.plugin)
        out.println("插件：${spec.id}（工具 ID：${manifest.id}）")
        out.println("版本：${manifest.version}")
        out.println("形态：${if (WebPluginPackagePolicy.isWeb(manifest)) "WEB / ${spec.webSource}" else spec.execution}")
        out.println("文件：${files.size} 个，${formatBytes(files.sumOf { it.size })}")
        if (spec.extensions.isNotEmpty()) {
            out.println("扩展：${spec.extensions.joinToString { "${it.id}/${it.point}" }}")
        }
        spec.harness?.let { harness ->
            out.println("Harness：工具 ${harness.tools.size}，节点 ${harness.nodes.size}，Provider ${harness.providers.size}")
        }
        if (spec.capabilities.isNotEmpty()) {
            out.println("能力：${spec.capabilities.joinToString { it.name + it.target.takeIf(String::isNotBlank)?.let { target -> ":$target" }.orEmpty() }}")
        }
        if (spec.dependencies.isNotEmpty()) {
            out.println("依赖：${spec.dependencies.joinToString { "${it.id} [${it.minimumVersion}, ${it.maximumVersionExclusive ?: "∞"})" }}")
        }
        if (spec.resources.isNotEmpty()) out.println("资源：${spec.resources.size} 项")
        if (manifest.installSteps.isNotEmpty()) out.println("安装步骤：${manifest.installSteps.size} 条")
        if (!manifest.launchCommand.isNullOrBlank()) out.println("启动命令：${manifest.launchCommand}")
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
}
