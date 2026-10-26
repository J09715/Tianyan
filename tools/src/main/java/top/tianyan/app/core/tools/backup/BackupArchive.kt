package top.tianyan.app.core.tools.backup

import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.LocalBackupManifest
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object BackupArchive {
    const val MAX_BYTES = 256L * 1024 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024
    const val MAX_FILES = 10_000
    val json = Json { encodeDefaults = true }

    fun safePath(path: String) {
        require(path.length in 1..1024 && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "备份包含不安全的文件路径" }
    }

    fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun write(destination: File, manifest: LocalBackupManifest, assets: File) {
        validateManifest(manifest)
        val text = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        require(text.size <= MAX_MANIFEST_BYTES) { "记录体积超过备份上限" }
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(text); zip.closeEntry()
            manifest.assets.forEach { asset ->
                val file = File(assets, asset.path)
                check(file.length() == asset.bytes && digest(file) == asset.sha256) { "备份暂存文件已变化" }
                zip.putNextEntry(ZipEntry("assets/${asset.path}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        require(destination.length() <= MAX_BYTES) { "备份文件超过 256 MiB" }
    }

    fun read(input: InputStream, parent: File): Pair<File, LocalBackupManifest> {
        parent.mkdirs()
        val stage = File(parent, "preview_${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            var bytes = 0L
            val names = mutableSetOf<String>()
            ZipInputStream(LimitedInput(input, MAX_BYTES)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    safePath(entry.name)
                    require(!entry.isDirectory && (entry.name == "manifest.json" || entry.name.startsWith("assets/"))) { "备份包含未知文件" }
                    require(names.size <= MAX_FILES && names.add(entry.name.lowercase())) { "备份存在重复文件或文件数量超限" }
                    val target = File(stage, entry.name)
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    target.outputStream().use { out ->
                        var fileBytes = 0L
                        val limit = if (entry.name == "manifest.json") MAX_MANIFEST_BYTES else MAX_FILE_BYTES
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            fileBytes += n; bytes += n
                            require(fileBytes <= limit && bytes <= MAX_BYTES) { "备份解压体积超过上限" }
                            out.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifestFile = File(stage, "manifest.json")
            require(manifestFile.isFile) { "缺少备份清单" }
            val manifest = try { json.decodeFromString<LocalBackupManifest>(manifestFile.readText()) }
                catch (_: Exception) { throw IllegalArgumentException("备份清单格式无效") }
            validateManifest(manifest)
            require(names == (manifest.assets.map { "assets/${it.path}" } + "manifest.json").map { it.lowercase() }.toSet()) { "备份文件清单不一致" }
            manifest.assets.forEach { asset ->
                val file = File(stage, "assets/${asset.path}")
                require(file.length() == asset.bytes && digest(file) == asset.sha256) { "备份文件校验失败" }
            }
            return stage to manifest
        } catch (failure: Exception) { stage.deleteRecursively(); throw failure }
    }

    fun validateManifest(manifest: LocalBackupManifest) {
        require(manifest.formatVersion == 1 && manifest.databaseVersion == 54) { "备份版本与当前天衍不兼容" }
        require(manifest.assets.size <= MAX_FILES && manifest.assets.sumOf { it.bytes } <= MAX_BYTES) { "备份体积或文件数量超限" }
        val names = mutableSetOf<String>()
        manifest.assets.forEach { asset ->
            safePath(asset.path)
            require(asset.path.substringBefore('/') in setOf("attachments", "skills", "harness_blobs") && '/' in asset.path &&
                asset.bytes in 0..MAX_FILE_BYTES && asset.sha256.matches(Regex("[0-9a-f]{64}")) && names.add(asset.path.lowercase())) { "备份资源清单无效" }
        }
        listOf(manifest.sourceFiles, manifest.sourceAttachments, manifest.sourceWorkspace).forEach {
            require(it.length in 5..4096 && '\u0000' !in it) { "备份来源路径无效" }
        }
        require(manifest.sourceAttachments.startsWith(manifest.sourceFiles + File.separator) &&
            manifest.sourceWorkspace.startsWith(manifest.sourceFiles + File.separator)) { "备份来源目录不一致" }
    }

    /** Resource scans reject links, rather than following a link out of the chosen storage root. */
    fun files(root: File): List<File> {
        if (!root.exists()) return emptyList()
        require(!Files.isSymbolicLink(root.toPath())) { "备份不支持符号链接" }
        val files = mutableListOf<File>()
        root.walkTopDown().onEnter { dir ->
            require(!Files.isSymbolicLink(dir.toPath())) { "备份不支持符号链接" }; true
        }.forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "备份不支持符号链接" }
            if (file.isFile) { require(files.size < MAX_FILES) { "备份文件数量超限" }; files += file }
        }
        return files
    }

    private class LimitedInput(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { count++; require(count <= max) { "备份文件超过上限" } } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also {
            if (it > 0) { count += it; require(count <= max) { "备份文件超过上限" } }
        }
    }
}
