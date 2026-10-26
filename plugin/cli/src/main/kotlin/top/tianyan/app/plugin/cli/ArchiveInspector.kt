package top.tianyan.app.plugin.cli

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PluginPackageProtocol
import top.tianyan.app.plugin.packages.WebPluginPackagePolicy

internal data class ArchiveInspection(
    val archive: Path,
    val manifest: ToolManifest,
    val files: Map<String, ContentFile>,
)

internal object ArchiveInspector {
    fun inspect(archive: Path): ArchiveInspection {
        val path = archive.toAbsolutePath().normalize()
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "插件包不存在：$path" }
        ZipFile(path.toFile()).use { zip ->
            val entries = mutableListOf<ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) entries += enumeration.nextElement()
            require(entries.size <= PluginProject.MAX_ARCHIVE_ENTRIES) { "插件包条目过多" }

            val names = mutableSetOf<String>()
            var manifestEntry: ZipEntry? = null
            entries.forEach { entry ->
                val name = entry.name.removeSuffix("/")
                require(name.isNotEmpty() && PluginPackageProtocol.safePayloadPath(name)) { "插件包包含不安全路径" }
                require(name == "manifest.json" || name == "payload" || name.startsWith("payload/")) {
                    "插件包只允许 manifest.json 与 payload/"
                }
                require(names.add(name)) { "插件包条目重复：$name" }
                if (name == "manifest.json") {
                    require(!entry.isDirectory) { "manifest.json 不能是目录" }
                    require(manifestEntry == null) { "插件包包含重复的 manifest.json" }
                    manifestEntry = entry
                }
                if (name == "payload") require(entry.isDirectory) { "payload 必须是目录" }
            }

            val manifestText = manifestEntry?.let { entry ->
                FileOps.decodeUtf8(FileOps.readBounded(zip.getInputStream(entry), PluginProject.MAX_MANIFEST_BYTES, "manifest.json"))
            } ?: error("插件包缺少 manifest.json")
            val manifest = try {
                PluginPackageProtocol.decode(manifestText)
            } catch (failure: Exception) {
                throw IllegalArgumentException("清单校验失败：${failure.message}", failure)
            }
            require(manifest.schemaVersion == 2) { "作者 CLI 只支持 v2 清单（schemaVersion=2）" }
            AuthoringChecks.validate(manifest)
            val web = WebPluginPackagePolicy.isWeb(manifest)
            if (web) require(entries.size <= WebPluginPackagePolicy.MAX_ARCHIVE_ENTRIES) { "Web 插件归档条目过多" }

            val limit = if (web) WebPluginPackagePolicy.MAX_ARCHIVE_BYTES else PluginProject.MAX_PROCESS_BYTES
            var total = manifestText.toByteArray(Charsets.UTF_8).size
            val files = sortedMapOf<String, ContentFile>()
            entries.filterNot { it.isDirectory }.forEach { entry ->
                val name = entry.name.removeSuffix("/")
                if (name == "manifest.json") return@forEach
                val relative = name.removePrefix("payload/")
                require(PluginPackageProtocol.safePayloadPath(relative)) { "payload 路径不安全：$relative" }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        total += count
                        require(total <= limit) { "插件包超过大小限制" }
                        digest.update(buffer, 0, count)
                    }
                }
                files[relative] = ContentFile(relative, size, digest.digest().hex())
            }

            val expected = requireNotNull(manifest.plugin).integrity
            val issues = compareIntegrity(expected, files.mapValues { it.value.sha256 })
            require(issues.isEmpty()) { "payload 校验失败：${issues.joinToString("；")}" }
            if (web) {
                require(files.size <= WebPluginPackagePolicy.MAX_FILES) { "Web 插件最多 ${WebPluginPackagePolicy.MAX_FILES} 个文件" }
                require(total <= WebPluginPackagePolicy.MAX_ARCHIVE_BYTES) { "Web 插件归档超过 3 MiB" }
                require(1 + impliedDirectories(files.keys) + files.size <= WebPluginPackagePolicy.MAX_TREE_ENTRIES) {
                    "Web 插件目录条目过多"
                }
            } else {
                require(files.size <= PluginProject.MAX_PROCESS_FILES) { "payload 文件过多" }
                require(total <= PluginProject.MAX_PROCESS_BYTES) { "插件包超过 8 GiB" }
            }
            return ArchiveInspection(path, manifest, files)
        }
    }

    private fun impliedDirectories(paths: Set<String>): Int {
        val directories = mutableSetOf<String>()
        paths.forEach { path ->
            val segments = path.split('/')
            var current = ""
            for (index in 0 until segments.lastIndex) {
                current = if (current.isEmpty()) segments[index] else "$current/${segments[index]}"
                directories += current
            }
        }
        return directories.size
    }
}
