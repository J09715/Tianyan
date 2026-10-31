package top.tianyan.app.core.tools

import top.tianyan.app.core.model.PluginLibraryChannel
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PluginPackageProtocol

object RemotePluginSource {
    fun catalogFile(channel: PluginLibraryChannel) = if (channel == PluginLibraryChannel.GITEE) "catalog-gitee.json" else "catalog.json"
    fun matches(release: RemotePluginRelease, manifest: ToolManifest): Boolean =
        manifest.schemaVersion == release.packageSchema && PluginPackageProtocol.identity(manifest) == release.pluginId &&
            manifest.id == release.toolId && manifest.version == release.version &&
            (manifest.plugin?.execution ?: "PROCESS") == release.execution
    fun base(channel: PluginLibraryChannel) = when (channel) {
        PluginLibraryChannel.GITHUB -> "https://raw.githubusercontent.com/tianyan-lab/tianyan-library/main"
        PluginLibraryChannel.GITEE -> "https://gitee.com/wang_ke_bin/tianyan-library/raw/main"
    }
    fun available(release: RemotePluginRelease, channel: PluginLibraryChannel): Boolean =
        channel == PluginLibraryChannel.GITHUB || release.parts.all { channel.name in it.mirrors }

    fun select(release: RemotePluginRelease, channel: PluginLibraryChannel): RemotePluginRelease {
        require(available(release, channel)) { "此插件未同步到 Gitee，请切换到 GitHub 渠道下载" }
        return if (channel == PluginLibraryChannel.GITHUB) release else release.copy(parts = release.parts.map {
            it.copy(url = it.mirrors.getValue(channel.name))
        })
    }
}
