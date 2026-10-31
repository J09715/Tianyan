package top.tianyan.app.plugin.packages

import top.tianyan.app.core.model.PackageExtension
import top.tianyan.app.core.model.ToolManifest

object PackageHarnessPolicy {
    fun extensions(manifest: ToolManifest): List<PackageExtension> = manifest.plugin?.harness?.let { harness ->
        harness.tools.map { PackageExtension(it.id, "TOOL") } + harness.nodes.map { PackageExtension(it.id, "WORKFLOW") } +
            harness.providers.map { PackageExtension(it.id, "PROVIDER") }
    }.orEmpty()

    fun validate(manifest: ToolManifest) {
        val spec = manifest.plugin ?: return
        val harness = spec.harness ?: return
        require(spec.execution == "PROCESS") { "仅 PROCESS 插件可以提供 Harness 扩展" }
        require(harness.entry.matches(Regex("[a-z0-9][a-z0-9._+-]{0,63}")) && harness.entry in manifest.commandLinks) {
            "MCP 入口必须是本插件已安装的命令入口"
        }
        val all = extensions(manifest)
        require(all.size in 1..32 && all.map { it.id }.distinct().size == all.size) { "Harness 扩展重复或数量无效" }
        require(all.all { it.id.matches(Regex("[a-z][a-z0-9-]{0,31}")) && it.id != "launch" }) { "Harness 扩展 ID 无效" }
        (harness.tools + harness.nodes).forEach {
            require(it.description.isNotBlank() && it.description.length <= 2_048)
            PackageSchemas.requireSupported(it.schema)
        }
        val rpcNames = harness.tools.map { it.rpcTool } + harness.nodes.map { it.rpcTool } + harness.providers.map { it.rpcTool }
        require(rpcNames.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,63}")) }) { "MCP 工具名无效" }
        harness.providers.forEach {
            require(it.api in setOf("OPENAI_COMPLETIONS", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"))
            require("plugin.${spec.id}.${it.id}".length <= 64) { "Provider 身份过长" }
            require(it.models.size in 1..64 && it.models.distinct().size == it.models.size &&
                it.models.all { model -> model.isNotBlank() && model.length <= 256 && model.none(Char::isISOControl) }) { "Provider 模型列表无效" }
        }
    }
}
