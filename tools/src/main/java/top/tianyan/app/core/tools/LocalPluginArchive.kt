package top.tianyan.app.core.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PluginPackageProtocol

/** Bounded extraction shared by URI import and filesystem tests. ZIP entries never become symlinks. */
internal object LocalPluginArchive {
    const val MAX_MANIFEST = 1024 * 1024L
    const val MAX_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 20_001

    fun entryName(raw: String): String {
        val name = raw.removeSuffix("/")
        require(PluginPackageProtocol.safePayloadPath(name)) { "插件包包含不安全路径" }
        require(name == "manifest.json" || name == "payload" || name.startsWith("payload/")) { "插件包只允许 manifest.json 与 payload/" }
        return name
    }

    fun readManifest(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_MANIFEST) { "manifest.json 过大" }
            bytes.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder()
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun inspect(input: InputStream, checkCancellation: () -> Unit = {}, maximumBytes: Long = MAX_BYTES,
        maximumEntries: Int = MAX_ENTRIES): String = ZipInputStream(input.buffered()).use { zip ->
        var entries = 0
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCancellation()
            val entry = zip.nextEntry ?: break
            require(++entries <= maximumEntries) { "插件包条目过多" }
            val name = entryName(entry.name)
            if (name == "manifest.json" && !entry.isDirectory) return readManifest(zip)
            while (true) {
                checkCancellation()
                val count = zip.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= maximumBytes) { "预览扫描超过大小限制" }
            }
        }
        error("插件包缺少 manifest.json")
    }

    fun extract(input: InputStream, staging: File, total: Long?, progress: (LocalPluginImportProgress) -> Unit, checkCancellation: () -> Unit = {},
        maximumBytes: Long = MAX_BYTES, maximumEntries: Int = MAX_ENTRIES): ToolManifest {
        var text: String? = null
        var bytes = 0L
        var lastReport = 0L
        val names = mutableSetOf<String>()
        val hashes = mutableMapOf<String, String>()
        val root = staging.canonicalFile.toPath()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                checkCancellation()
                val entry = zip.nextEntry ?: break
                val name = entryName(entry.name)
                require(names.add(name) && names.size <= maximumEntries) { "插件包条目重复或过多" }
                if (entry.isDirectory) { require(name != "manifest.json"); continue }
                require(name != "payload") { "payload 必须是目录" }
                if (name == "manifest.json") {
                    text = readManifest(zip)
                    bytes += text.toByteArray(Charsets.UTF_8).size
                } else {
                    val target = File(staging, name)
                    require(target.canonicalFile.toPath().startsWith(root)) { "插件路径越界" }
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    val digest = MessageDigest.getInstance("SHA-256")
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancellation()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= maximumBytes) { "插件包超过大小限制" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            if (bytes - lastReport >= 4L * 1024 * 1024) {
                                lastReport = bytes
                                progress(LocalPluginImportProgress(bytes, null, name))
                            }
                        }
                    }
                    hashes[name.removePrefix("payload/")] = digest.digest().joinToString("") { "%02x".format(it) }
                }
                require(bytes <= maximumBytes) { "插件包超过大小限制" }
            }
        }
        val manifestText = requireNotNull(text) { "插件包缺少 manifest.json" }
        val manifest = PluginPackageProtocol.decode(manifestText)
        ToolManifestValidator.validateAll(listOf(manifest))
        manifest.plugin?.let { require(it.integrity == hashes) { "payload 文件集合或 SHA-256 与清单不一致" } }
        if (top.tianyan.app.plugin.packages.WebPluginPackagePolicy.isWeb(manifest)) {
            val payloadBytes = hashes.keys.sumOf { File(staging, "payload/$it").length() }
            require(payloadBytes <= top.tianyan.app.plugin.packages.WebPluginPackagePolicy.MAX_PAYLOAD_BYTES) { "Web 插件资源超过大小限制" }
        }
        File(staging, "manifest.json").writeText(manifestText)
        File(staging, "payload").mkdirs()
        ProotWebPackagePreflight.verify(manifest, staging, checkCancellation)
        progress(LocalPluginImportProgress(total ?: bytes, total, "校验完成"))
        return manifest
    }
}
