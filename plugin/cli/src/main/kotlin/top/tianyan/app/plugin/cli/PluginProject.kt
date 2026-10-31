package top.tianyan.app.plugin.cli

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PluginPackageProtocol
import top.tianyan.app.plugin.packages.WebPluginPackagePolicy

internal data class ContentFile(val path: String, val size: Long, val sha256: String, val source: Path? = null)

internal class PluginProject private constructor(
    val root: Path,
    val manifest: ToolManifest,
    val files: Map<String, ContentFile>,
    val directories: List<String>,
) {
    val payloadBytes: Long get() = files.values.sumOf { it.size }
    val integrity: Map<String, String> get() = files.mapValues { it.value.sha256 }

    fun manifestWithIntegrity(): ToolManifest {
        val spec = requireNotNull(manifest.plugin)
        return manifest.copy(plugin = spec.copy(integrity = integrity))
    }

    fun encodedManifest(): ByteArray {
        val updated = manifestWithIntegrity()
        PluginPackageProtocol.validate(updated)
        return ManifestCodec.encode(updated).toByteArray(Charsets.UTF_8)
    }

    fun writeManifest(updated: ToolManifest = manifestWithIntegrity()) {
        PluginPackageProtocol.validate(updated)
        val bytes = ManifestCodec.encode(updated).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "manifest.json 超过 1 MiB" }
        FileOps.writeAtomically(root.resolve("manifest.json"), bytes)
    }

    fun integrityIssues(): List<String> = compareIntegrity(manifest.plugin?.integrity.orEmpty(), integrity)

    companion object {
        const val MAX_MANIFEST_BYTES = 1024 * 1024L
        const val MAX_PROCESS_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 20_001
        const val MAX_PROCESS_FILES = 20_000

        fun load(root: Path): PluginProject {
            val directory = root.toAbsolutePath().normalize()
            require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "工程目录不存在：$directory" }
            val manifestPath = directory.resolve("manifest.json")
            require(Files.isRegularFile(manifestPath, NOFOLLOW_LINKS)) { "工程缺少 manifest.json 或清单为符号链接" }
            val text = FileOps.readUtf8(manifestPath, MAX_MANIFEST_BYTES, "manifest.json")
            val decoded = try {
                PluginPackageProtocol.decode(text)
            } catch (failure: Exception) {
                throw IllegalArgumentException("清单校验失败：${failure.message}", failure)
            }
            require(decoded.schemaVersion == 2) { "作者 CLI 只支持 v2 清单（schemaVersion=2）" }
            require(decoded.plugin != null) { "v2 清单缺少 plugin 声明" }
            AuthoringChecks.validate(decoded)

            val payload = directory.resolve("payload")
            require(Files.isDirectory(payload, NOFOLLOW_LINKS)) { "payload 必须为真实目录" }
            val files = sortedMapOf<String, ContentFile>()
            val directories = mutableListOf<String>()
            Files.walkFileTree(payload, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink) { "payload 不允许符号链接" }
                    if (dir != payload) directories += payload.relativize(dir).joinToString("/")
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(attrs.isRegularFile && !attrs.isSymbolicLink) { "payload 不允许符号链接或特殊文件" }
                    val name = payload.relativize(file).joinToString("/")
                    require(PluginPackageProtocol.safePayloadPath(name)) { "payload 路径不安全：$name" }
                    files[name] = ContentFile(name, attrs.size(), FileOps.sha256(file), file)
                    return FileVisitResult.CONTINUE
                }
            })
            return PluginProject(directory, decoded, files, directories.sorted()).also { it.checkLimits() }
        }
    }

    private fun checkLimits() {
        val manifestBytes = encodedManifest().size
        require(manifestBytes <= MAX_MANIFEST_BYTES) { "manifest.json 超过 1 MiB" }
        val entries = 2 + directories.size + files.size
        if (WebPluginPackagePolicy.isWeb(manifest)) {
            require(files.size <= WebPluginPackagePolicy.MAX_FILES) { "Web 插件最多 ${WebPluginPackagePolicy.MAX_FILES} 个文件" }
            require(payloadBytes <= WebPluginPackagePolicy.MAX_PAYLOAD_BYTES) { "Web 插件资源超过 2 MiB" }
            require(entries <= WebPluginPackagePolicy.MAX_ARCHIVE_ENTRIES) { "Web 插件归档条目过多" }
            require(payloadBytes + manifestBytes <= WebPluginPackagePolicy.MAX_ARCHIVE_BYTES) { "Web 插件归档超过 3 MiB" }
            require(1 + directories.size + files.size <= WebPluginPackagePolicy.MAX_TREE_ENTRIES) { "Web 插件目录条目过多" }
        } else {
            require(files.size <= MAX_PROCESS_FILES) { "payload 文件过多" }
            require(payloadBytes <= MAX_PROCESS_BYTES) { "插件包超过 8 GiB" }
            require(entries <= MAX_ARCHIVE_ENTRIES) { "插件包条目过多" }
            require(payloadBytes + manifestBytes <= MAX_PROCESS_BYTES) { "插件包超过 8 GiB" }
        }
    }
}
