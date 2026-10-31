package top.tianyan.app.core.tools

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.plugin.packages.PluginPackageProtocol

/** Host-side packaging only: does not execute project scripts or alter the release manifest. */
object PluginDebugPackager {
    fun pack(project: File, checkCancellation: () -> Unit = {}): File {
        val root = project.canonicalFile.toPath()
        val manifestPath = root.resolve("manifest.json")
        require(Files.isRegularFile(manifestPath, NOFOLLOW_LINKS)) { "工程缺少 manifest.json 或清单为符号链接" }
        val source = Files.newInputStream(manifestPath).use(LocalPluginArchive::readManifest)
        val manifest = PluginPackageProtocol.decode(source)
        require(manifest.schemaVersion == 2) { "一键调试仅支持 v2 PROCESS 插件工程" }
        val payload = root.resolve("payload")
        require(Files.isDirectory(payload, NOFOLLOW_LINKS)) { "payload 必须为真实目录" }
        val dist = root.resolve("dist")
        if (!Files.exists(dist, NOFOLLOW_LINKS)) Files.createDirectory(dist)
        require(Files.isDirectory(dist, NOFOLLOW_LINKS)) { "dist 必须为真实目录" }
        val temporary = Files.createTempFile(dist, ".plugin-debug-", ".tmp").toFile()
        try {
            val hashes = sortedMapOf<String, String>()
            var bytes = 0L
            var entries = 0
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                Files.walkFileTree(payload, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkCancellation()
                        require(++entries <= LocalPluginArchive.MAX_ENTRIES - 1) { "payload 条目过多" }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkCancellation()
                        require(attrs.isRegularFile && !attrs.isSymbolicLink) { "payload 不允许符号链接或特殊文件" }
                        require(++entries <= LocalPluginArchive.MAX_ENTRIES - 1) { "payload 条目过多" }
                        val name = payload.relativize(file).joinToString("/")
                        require(PluginPackageProtocol.safePayloadPath("payload/$name")) { "payload 包含不安全路径" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.putNextEntry(ZipEntry("payload/$name"))
                        Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancellation()
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= LocalPluginArchive.MAX_BYTES) { "插件包超过大小限制" }
                                zip.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                        hashes[name] = digest.digest().hex()
                        return FileVisitResult.CONTINUE
                    }
                })
                val snapshot = manifest.copy(plugin = requireNotNull(manifest.plugin).copy(integrity = hashes))
                val fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(Json.encodeToString(ToolManifest.serializer(), snapshot).toByteArray()).hex()
                val snapshotId = java.util.UUID.randomUUID().toString().replace("-", "")
                val version = manifest.version.substringBefore('+') + "+tianyan.debug.${fingerprint.take(16)}.$snapshotId"
                val debug = snapshot.copy(version = version)
                PluginPackageProtocol.validate(debug)
                ToolManifestValidator.validateAll(listOf(debug))
                val encoded = Json.encodeToString(ToolManifest.serializer(), debug).toByteArray()
                require(encoded.size <= LocalPluginArchive.MAX_MANIFEST && bytes + encoded.size <= LocalPluginArchive.MAX_BYTES) {
                    "插件清单或包超过大小限制"
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(encoded)
                zip.closeEntry()
            }
            checkCancellation()
            // Unique snapshots keep an open confirmation bound to its original bytes.
            val archive = File(dist.toFile(), "${manifest.id}-debug-${java.util.UUID.randomUUID()}.txplugin")
            check(temporary.renameTo(archive)) { "无法保存调试插件包" }
            return archive
        } finally {
            temporary.delete()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
