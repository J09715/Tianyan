package top.tianyan.app.plugin.cli

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import top.tianyan.app.plugin.packages.PluginPackageProtocol
import top.tianyan.app.plugin.packages.WebPluginPackagePolicy

internal object ArchiveWriter {
    private val fixedTime = LocalDateTime.of(1980, 1, 1, 0, 0)

    fun pack(project: PluginProject, requestedOutput: Path, writeManifest: Boolean): Path {
        val target = requestedOutput.toAbsolutePath().normalize()
        require(!target.startsWith(project.root.resolve("payload"))) { "输出文件不能放在 payload 目录内" }
        require(target != project.root.resolve("manifest.json")) { "输出文件不能覆盖 manifest.json" }
        val manifest = project.manifestWithIntegrity()
        PluginPackageProtocol.validate(manifest)
        val manifestBytes = ManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= PluginProject.MAX_MANIFEST_BYTES) { "manifest.json 超过 1 MiB" }
        val entries = 2 + project.directories.size + project.files.size
        val archiveBytes = project.payloadBytes + manifestBytes.size
        if (WebPluginPackagePolicy.isWeb(manifest)) {
            require(entries <= WebPluginPackagePolicy.MAX_ARCHIVE_ENTRIES) { "Web 插件归档条目过多" }
            require(archiveBytes <= WebPluginPackagePolicy.MAX_ARCHIVE_BYTES) { "Web 插件归档超过 3 MiB" }
        } else {
            require(entries <= PluginProject.MAX_ARCHIVE_ENTRIES) { "插件包条目过多" }
            require(archiveBytes <= PluginProject.MAX_PROCESS_BYTES) { "插件包超过 8 GiB" }
        }

        val parent = requireNotNull(target.parent) { "无法确定输出目录：$target" }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".tianyan-plugin-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temporary), Charsets.UTF_8).use { zip ->
                put(zip, "manifest.json", manifestBytes)
                put(zip, "payload/", ByteArray(0))
                project.directories.forEach { put(zip, "payload/$it/", ByteArray(0)) }
                project.files.toSortedMap().forEach { (name, file) ->
                    val source = requireNotNull(file.source) { "缺少 payload 源文件：$name" }
                    val entry = ZipEntry("payload/$name").apply { timeLocal = fixedTime }
                    zip.putNextEntry(entry)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            digest.update(buffer, 0, count)
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                    require(size == file.size && digest.digest().hex() == file.sha256) {
                        "打包期间 payload 发生变化：$name"
                    }
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        if (writeManifest) project.writeManifest(manifest)
        return target
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { timeLocal = fixedTime })
        if (bytes.isNotEmpty()) zip.write(bytes)
        zip.closeEntry()
    }
}
