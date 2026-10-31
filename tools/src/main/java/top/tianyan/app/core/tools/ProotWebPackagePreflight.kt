package top.tianyan.app.core.tools

import java.io.File
import java.io.InputStream
import java.util.UUID
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.WebPluginPackagePolicy
import top.tianyan.app.runtime.service.ProotWebBootstrap

/** Full bounded preview for PRoot packages; the same verification also runs before store commit. */
internal object ProotWebPackagePreflight {
    fun required(manifest: ToolManifest) = WebPluginPackagePolicy.isWeb(manifest) && manifest.plugin!!.webSource == "PROOT_STATIC"

    fun verify(manifest: ToolManifest, staging: File, checkCancellation: () -> Unit) {
        if (!required(manifest)) return
        val assets = manifest.plugin!!.integrity.keys.associateWith { name ->
            checkCancellation()
            File(staging, "payload/$name").readBytes()
        }
        ProotWebBootstrap.requireFits(assets)
        checkCancellation()
    }

    fun preview(cache: File, expected: ToolManifest, input: () -> InputStream, checkCancellation: () -> Unit) {
        check(cache.mkdirs() || cache.isDirectory)
        val staging = File(cache, "web-preview-${UUID.randomUUID()}")
        check(staging.mkdir())
        try {
            val actual = input().use { LocalPluginArchive.extract(it, staging, null, {}, checkCancellation,
                WebPluginPackagePolicy.MAX_ARCHIVE_BYTES, WebPluginPackagePolicy.MAX_ARCHIVE_ENTRIES) }
            require(actual == expected) { "插件清单已变化，请重新预览" }
        } finally { check(staging.deleteRecursively()) { "无法清理插件预览" } }
    }
}
